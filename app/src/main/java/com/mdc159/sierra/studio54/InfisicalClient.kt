package com.mdc159.sierra.studio54

import com.mdc159.sierra.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/**
 * Thrown when the vault cannot be read. [unreachable] is true for network failures (the
 * phone is probably off the private network), false for answers the server did give.
 * Messages never contain a secret value or a token.
 */
class VaultException(
    message: String,
    val code: Int? = null,
    val unreachable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal Infisical REST client for a machine identity using universal auth.
 *
 * Endpoints, taken from Infisical's server source and its official SDKs (not guessed):
 * - `POST /api/v1/auth/universal-auth/login` with `{clientId, clientSecret}` returns
 *   `{accessToken, expiresIn, accessTokenMaxTTL, tokenType}`; the TTLs are in seconds.
 * - `POST /api/v1/auth/token/renew` with `{accessToken}` extends the token's life, up to
 *   `accessTokenMaxTTL` after login, and returns the same shape.
 * - `GET /api/v3/secrets/raw?workspaceId=&environment=&secretPath=&viewSecretValue=true&…`
 *   returns `{secrets: [{secretKey, secretValue, …}], imports: [{secrets: […]}]}`. This is the
 *   endpoint the official SDKs call. The server marks it deprecated in favour of
 *   `GET /api/v4/secrets?projectId=…`, which has the same response shape; [LIST_PATH] is the
 *   one place to change if the vault ever drops v3.
 */
class InfisicalClient(
    private val credentials: Studio54Credentials,
    httpClient: OkHttpClient = Http.client,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = credentials.apiUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Vault URL is not valid.")

    private class Token(val value: String, val expiresAt: Long, val maxExpiresAt: Long?)

    @Volatile
    private var token: Token? = null

    /** True while a usable access token is held (for status and tests). */
    val hasToken: Boolean get() = token?.let { it.expiresAt > clock() } == true

    /**
     * Reads every secret in [environment] at [path] of [projectId], values included.
     * Secrets the project defines win over secrets it imports from elsewhere.
     */
    fun listSecrets(projectId: String, environment: String = ENVIRONMENT, path: String = SECRET_PATH): Map<String, String> {
        val url = base.newBuilder()
            .encodedPath(LIST_PATH)
            .addQueryParameter("workspaceId", projectId)
            .addQueryParameter("environment", environment)
            .addQueryParameter("secretPath", path)
            .addQueryParameter("viewSecretValue", "true")
            .addQueryParameter("expandSecretReferences", "true")
            .addQueryParameter("recursive", "false")
            .addQueryParameter("include_imports", "true")
            .build()
        val body = authorised { bearer ->
            Request.Builder().url(url).header("Authorization", "Bearer $bearer").get().build()
        }
        return try {
            parseSecrets(JSONObject(body))
        } catch (e: JSONException) {
            throw VaultException("The vault sent a secret list Sierra couldn't read.", cause = e)
        }
    }

    /** Forgets the access token so the next call logs in again. */
    fun invalidateToken() {
        token = null
    }

    /** Runs [build] with a valid bearer token, logging in again once if the vault says 401. */
    private fun authorised(build: (String) -> Request): String {
        val first = execute(build(currentToken()))
        if (first.code != 401) return first.okBody()
        invalidateToken()
        val second = execute(build(currentToken()))
        return second.okBody()
    }

    private fun currentToken(): String {
        val now = clock()
        val t = token
        if (t != null && t.expiresAt - now > RENEW_MARGIN_MILLIS) return t.value
        if (t != null && (t.maxExpiresAt == null || t.maxExpiresAt - now > RENEW_MARGIN_MILLIS)) {
            try {
                return renew(t).also { token = it }.value
            } catch (e: VaultException) {
                if (e.unreachable) throw e
                // The server refused the renewal (expired or revoked): fall through to a fresh login.
            }
        }
        return login().also { token = it }.value
    }

    private fun login(): Token {
        val body = JSONObject()
            .put("clientId", credentials.clientId)
            .put("clientSecret", credentials.clientSecret)
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath(LOGIN_PATH).build())
            .post(body.toString().toRequestBody(JSON))
            .build()
        val issuedAt = clock()
        return parseToken(execute(request).okBody("Sign-in"), issuedAt, maxFrom = null)
    }

    private fun renew(current: Token): Token {
        val body = JSONObject().put("accessToken", current.value)
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath(RENEW_PATH).build())
            .post(body.toString().toRequestBody(JSON))
            .build()
        return parseToken(execute(request).okBody("Token renewal"), clock(), maxFrom = current)
    }

    private fun parseToken(json: String, issuedAt: Long, maxFrom: Token?): Token {
        try {
            val obj = JSONObject(json)
            val value = obj.getString("accessToken")
            val ttlSeconds = obj.getLong("expiresIn")
            val maxTtlSeconds = obj.optLong("accessTokenMaxTTL", 0)
            // The max TTL counts from the original login, not from each renewal.
            val maxExpiresAt = maxFrom?.maxExpiresAt
                ?: maxTtlSeconds.takeIf { it > 0 }?.let { issuedAt + it * 1000 }
            var expiresAt = issuedAt + ttlSeconds * 1000
            if (maxExpiresAt != null && expiresAt > maxExpiresAt) expiresAt = maxExpiresAt
            return Token(value, expiresAt, maxExpiresAt)
        } catch (e: JSONException) {
            throw VaultException("The vault sent a sign-in reply Sierra couldn't read.", cause = e)
        }
    }

    private class Reply(val code: Int, val body: String) {
        fun okBody(what: String = "Vault"): String {
            if (code in 200..299) return body
            val detail = errorDetail(body)
            val text = when (code) {
                401 -> "$what rejected: check the client id and secret, or the agent was removed."
                403 -> "$what forbidden: this agent may not read that project."
                404 -> "$what not found: check the vault URL and project id."
                else -> "$what error $code"
            }
            throw VaultException(if (detail.isEmpty()) text else "$text ($detail)", code = code)
        }
    }

    private fun execute(request: Request): Reply {
        try {
            http.newCall(request).execute().use { response ->
                return Reply(response.code, response.body?.string().orEmpty())
            }
        } catch (e: UnknownServiceException) {
            // Android blocks plain http unless the network security config allows the host.
            throw VaultException(
                "Plain http to ${credentials.vaultHost} is blocked by this build's network security rules.",
                unreachable = true,
                cause = e,
            )
        } catch (e: IOException) {
            throw VaultException("Vault unreachable: ${Http.describe(e)}", unreachable = true, cause = e)
        }
    }

    companion object {
        const val ENVIRONMENT = "prod"
        const val SECRET_PATH = "/"
        const val LOGIN_PATH = "/api/v1/auth/universal-auth/login"
        const val RENEW_PATH = "/api/v1/auth/token/renew"
        const val LIST_PATH = "/api/v3/secrets/raw"

        /** Renew or re-login when less than this much of the token's life is left. */
        const val RENEW_MARGIN_MILLIS = 60_000L
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Imported secrets first, then the project's own, so the project's own win. */
        fun parseSecrets(obj: JSONObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val imports = obj.optJSONArray("imports")
            if (imports != null) {
                for (i in 0 until imports.length()) {
                    addAll(out, imports.getJSONObject(i).optJSONArray("secrets"))
                }
            }
            addAll(out, obj.getJSONArray("secrets"))
            return out
        }

        private fun addAll(out: MutableMap<String, String>, secrets: JSONArray?) {
            if (secrets == null) return
            for (i in 0 until secrets.length()) {
                val s = secrets.getJSONObject(i)
                val key = s.optString("secretKey")
                if (key.isEmpty() || s.optBoolean("secretValueHidden", false)) continue
                out[key] = s.optString("secretValue")
            }
        }

        /** The `message` of an Infisical error body, shortened. Never echoes request values. */
        fun errorDetail(body: String): String = try {
            JSONObject(body).optString("message").replace(Regex("\\s+"), " ").trim().take(100)
        } catch (e: JSONException) {
            ""
        }
    }
}

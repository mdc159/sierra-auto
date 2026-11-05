package com.mdc159.sierra.studio54

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * A stateful stand-in for an Infisical server: universal-auth login, token renewal and the
 * raw secret list, with the same paths, field names and error shapes as the real one.
 */
class FakeInfisical : Dispatcher() {
    var clientId = "client-id"
    var clientSecret = "client-secret"
    var ttlSeconds = 7200L
    var maxTtlSeconds = 43200L

    /** projectId -> (secret name -> value). */
    val projects = mutableMapOf<String, Map<String, String>>()

    /** projectId -> imported secrets, lower precedence than the project's own. */
    val imports = mutableMapOf<String, Map<String, String>>()

    /** Values whose `secretValueHidden` flag the server sets (value withheld). */
    val hidden = mutableSetOf<String>()

    var rejectRenew = false
    var loginCount = 0
    var renewCount = 0
    var listCount = 0
    val validTokens = mutableSetOf<String>()
    val requests = mutableListOf<RecordedRequest>()

    /** Simulates the server revoking every token (agent removed, or token expired server-side). */
    fun revokeAllTokens() = validTokens.clear()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.requestUrl?.encodedPath ?: return error(404, "no url")
        return when {
            path == InfisicalClient.LOGIN_PATH && request.method == "POST" -> login(request)
            path == InfisicalClient.RENEW_PATH && request.method == "POST" -> renew(request)
            path == InfisicalClient.LIST_PATH && request.method == "GET" -> list(request)
            else -> error(404, "Route ${request.method}:$path not found")
        }
    }

    private fun login(request: RecordedRequest): MockResponse {
        // clone(): leave the body in place for the test to inspect afterwards.
        val body = JSONObject(request.body.clone().readUtf8())
        if (body.optString("clientId") != clientId || body.optString("clientSecret") != clientSecret) {
            return error(401, "Invalid credentials")
        }
        loginCount++
        val token = "token-$loginCount"
        validTokens += token
        return tokenReply(token)
    }

    private fun renew(request: RecordedRequest): MockResponse {
        val token = JSONObject(request.body.clone().readUtf8()).optString("accessToken")
        if (rejectRenew || token !in validTokens) return error(401, "Failed to renew token")
        renewCount++
        return tokenReply(token)
    }

    private fun tokenReply(token: String) = json(
        JSONObject()
            .put("accessToken", token)
            .put("expiresIn", ttlSeconds)
            .put("accessTokenMaxTTL", maxTtlSeconds)
            .put("tokenType", "Bearer"),
    )

    private fun list(request: RecordedRequest): MockResponse {
        listCount++
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
        if (token == null || token !in validTokens) return error(401, "Unauthorized")
        val projectId = request.requestUrl?.queryParameter("workspaceId")
        val secrets = projects[projectId] ?: return error(404, "Project with ID '$projectId' not found")
        val reply = JSONObject().put("secrets", secretArray(secrets))
        imports[projectId]?.let {
            reply.put(
                "imports",
                JSONArray().put(
                    JSONObject().put("secretPath", "/").put("environment", "prod").put("secrets", secretArray(it)),
                ),
            )
        }
        return json(reply)
    }

    private fun secretArray(secrets: Map<String, String>): JSONArray {
        val arr = JSONArray()
        secrets.forEach { (name, value) ->
            val isHidden = name in hidden
            arr.put(
                JSONObject()
                    .put("id", "id-$name")
                    .put("workspace", "w")
                    .put("environment", "prod")
                    .put("version", 1)
                    .put("type", "shared")
                    .put("secretKey", name)
                    .put("secretValue", if (isHidden) "" else value)
                    .put("secretValueHidden", isHidden)
                    .put("secretComment", "")
                    .put("secretPath", "/"),
            )
        }
        return arr
    }

    private fun json(obj: JSONObject) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(obj.toString())

    private fun error(code: Int, message: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("statusCode", code).put("message", message).put("error", "Error").toString())
}

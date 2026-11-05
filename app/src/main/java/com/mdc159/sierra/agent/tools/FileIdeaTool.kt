package com.mdc159.sierra.agent.tools

import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * `file_idea`: the "speak a feature idea" loop in docs/IDEA-LOOP.md. Creates a GitHub
 * issue on the Sierra repo labelled [LABEL] (`idea-voice`), deliberately not `idea`:
 * the model that calls this tool also reads text from OpenStreetMap (place names and
 * addresses), so an issue it files must not start the cloud agent on its own. The owner
 * reads the issue and adds the `idea` label from the GitHub app to run it.
 *
 * The fine-grained token (Issues: read and write on this one repo) comes from
 * IdeaSettingsActivity and is held encrypted by ConfigStore; it never appears in a log
 * or in the result the model reads.
 */
class FileIdeaTool(
    private val token: () -> String,
    private val client: OkHttpClient = Http.client,
    private val repo: String = REPO,
    private val apiBase: String = "https://api.github.com",
) : Tool {

    override val name = "file_idea"

    override val description =
        "Files a feature idea or bug report for the Sierra app itself as a GitHub issue. Use it only when the " +
            "driver asks to file, save or note an idea for the app. Returns the issue number; tell the driver that number."

    override val parameters: JSONObject = ToolSchema.obj(
        "title" to ToolSchema.string("A short title, under ten words."),
        "body" to ToolSchema.string("The idea in the driver's own words, lightly cleaned up."),
        required = listOf("title", "body"),
    )

    override fun status(args: JSONObject): String = "Filing your idea…"

    override fun run(args: JSONObject): String {
        val title = args.optString("title").trim().take(MAX_TITLE)
        val body = args.optString("body").trim()
        if (title.isEmpty() && body.isEmpty()) throw ToolException("Say what the idea is.")
        val pat = token().trim()
        if (pat.isEmpty()) {
            throw ToolException("No GitHub token yet. Enter one in Idea settings in the Sierra app on the phone.")
        }
        val json = JSONObject()
            .put("title", title.ifEmpty { body.take(MAX_TITLE) })
            .put("body", (body.ifEmpty { title }) + "\n\n" + FOOTER)
            .put("labels", JSONArray().put(LABEL))
        val request = Request.Builder()
            .url("$apiBase/repos/$repo/issues")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Http.USER_AGENT)
            .post(json.toString().toRequestBody(JSON))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ToolException(Http.describe(e), e)
        }
        response.use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ToolException(Http.describe(e), e)
            }
            if (!r.isSuccessful) throw ToolException(describeFailure(r.code, text))
            return try {
                val issue = JSONObject(text)
                val number = issue.getInt("number")
                JSONObject()
                    .put("ok", true)
                    .put("issue_number", number)
                    .put("label", LABEL)
                    .put("note", "Tell the driver: filed as issue number $number, to review on GitHub later.")
                    .toString()
            } catch (e: JSONException) {
                throw ToolException("GitHub sent a reply Sierra couldn't read.", e)
            }
        }
    }

    /**
     * For the phone's "Test token" button: proves the token can see the repo without
     * creating anything. Returns a one-line verdict; throws [ToolException] on failure.
     */
    fun checkToken(): String {
        val pat = token().trim()
        if (pat.isEmpty()) throw ToolException("Enter a token first.")
        val request = Request.Builder()
            .url("$apiBase/repos/$repo")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Http.USER_AGENT)
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ToolException(Http.describe(e), e)
        }
        response.use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ToolException(Http.describe(e), e)
            }
            if (!r.isSuccessful) throw ToolException(describeFailure(r.code, text))
            val canWrite = runCatching { JSONObject(text).optJSONObject("permissions")?.optBoolean("push") }.getOrNull()
            return when (canWrite) {
                true -> "Token OK: $repo is reachable and you have write access."
                false -> "Token reaches $repo, but this account cannot write to it."
                null -> "Token OK: $repo is reachable."
            }
        }
    }

    companion object {
        const val REPO = "mdc159/sierra-auto"

        /** Not `idea`: that label runs the cloud agent, and only the owner should apply it. */
        const val LABEL = "idea-voice"
        const val MAX_TITLE = 80
        private const val FOOTER =
            "_Spoken from the truck through Sierra Talk. Add the `idea` label to hand it to the cloud agent._"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun describeFailure(code: Int, body: String): String = when (code) {
            401 -> "GitHub rejected the token. Check it in Idea settings on the phone."
            403 -> "The GitHub token can't create issues on this repo. It needs Issues: read and write."
            404 -> "GitHub can't see the repo with this token. Give the token access to $REPO."
            422 -> "GitHub refused the issue: ${AgentClient.errorDetail(body)}".trimEnd(' ', ':')
            else -> "GitHub error $code: ${AgentClient.errorDetail(body)}".trimEnd(' ', ':')
        }
    }
}

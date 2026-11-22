package com.mdc159.sierra.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Agent settings, encrypted at rest with a key held in the Android Keystore. */
object ConfigStore {
    private const val FILE = "sierra_agent_config"
    private const val TAG = "SierraConfig"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Why the encrypted store could not be opened in this run of the app, or null. While set,
     * [load] and [save] work on a copy in memory, starting from the defaults: the file on disk
     * is left exactly as it is (it is never deleted), and the next start of the app tries to
     * open it again. Deleting the file — the old behavior — turned a keystore reset into the
     * loss of every saved agent and bridge setting.
     */
    @Volatile
    var openError: String? = null
        private set

    /** The settings for this run when the store is unavailable. */
    @Volatile
    private var inMemory: AgentConfig? = null

    /** The idea token for this run when the store is unavailable. */
    @Volatile
    private var inMemoryIdeaToken: String? = null

    /**
     * How the encrypted file is opened. Injectable so a unit test can simulate an open
     * failure (the Android Keystore does not exist on the JVM, so the real opener fails there).
     */
    @Volatile
    internal var opener: (Context) -> SharedPreferences = { openEncrypted(it) }

    /** The encrypted store, or null when it cannot be opened in this run (see [openError]). */
    private fun prefs(context: Context): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            if (openError != null) return null
            return try {
                opener(context.applicationContext).also { prefs = it }
            } catch (e: Exception) {
                // Keystore and file errors carry no setting values; the saved file is kept.
                openError = "${e.javaClass.simpleName}: ${redact(e.message)}"
                runCatching {
                    Log.w(
                        TAG,
                        "agent settings store could not be opened; using an in-memory copy for this run, file kept ($openError)",
                    )
                }
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openEncrypted(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): AgentConfig {
        val p = prefs(context) ?: return inMemory ?: AgentConfig()
        return AgentConfig(
            baseUrl = p.getString("base_url", "").orEmpty(),
            apiKey = p.getString("api_key", "").orEmpty(),
            model = p.getString("model", "").orEmpty(),
            sttUrl = p.getString("stt_url", "").orEmpty(),
            sttKey = p.getString("stt_key", "").orEmpty(),
            sttModel = p.getString("stt_model", "").orEmpty().ifBlank { AgentConfig.DEFAULT_STT_MODEL },
            profile = AgentProfile.entries.firstOrNull { it.name == p.getString("agent_profile", null) } ?: AgentProfile.GATEWAY,
            bridgeUrl = p.getString("bridge_url", "").orEmpty(),
            bridgeKey = p.getString("bridge_key", "").orEmpty(),
            bridgeModel = p.getString("bridge_model", "").orEmpty().ifBlank { AgentConfig.DEFAULT_BRIDGE_MODEL },
        )
    }

    fun save(context: Context, config: AgentConfig) {
        val p = prefs(context)
        if (p == null) {
            inMemory = config
            return
        }
        p.edit()
            .putString("base_url", config.baseUrl.trim())
            .putString("api_key", config.apiKey.trim())
            .putString("model", config.model.trim())
            .putString("stt_url", config.sttUrl.trim())
            .putString("stt_key", config.sttKey.trim())
            .putString("stt_model", config.sttModel.trim())
            .putString("agent_profile", config.profile.name)
            .putString("bridge_url", config.bridgeUrl.trim())
            .putString("bridge_key", config.bridgeKey.trim())
            .putString("bridge_model", config.bridgeModel.trim())
            .apply()
    }

    /**
     * The fine-grained GitHub token the `file_idea` tool uses (Issues: read and write on
     * mdc159/sierra-auto only). Kept in the same encrypted store as the agent key, under
     * its own key so saving the agent settings never touches it.
     */
    fun loadIdeaToken(context: Context): String =
        prefs(context)?.getString(IDEA_TOKEN, "")?.orEmpty() ?: inMemoryIdeaToken.orEmpty()

    fun saveIdeaToken(context: Context, token: String) {
        val p = prefs(context)
        if (p == null) {
            inMemoryIdeaToken = token
            return
        }
        p.edit().putString(IDEA_TOKEN, token.trim()).apply()
    }

    /**
     * Test seam: clears the cached store, the in-memory copy and any open error, and
     * installs [open] as the opener (the real encrypted open by default).
     */
    @VisibleForTesting
    fun resetForTest(open: (Context) -> SharedPreferences = { openEncrypted(it) }) {
        synchronized(this) {
            prefs = null
            openError = null
            inMemory = null
            inMemoryIdeaToken = null
            opener = open
        }
    }

    /**
     * One line, bounded, with anything shaped like a credential or a query string removed.
     * The open error is also shown on the settings screen, so it must never carry a value.
     */
    private fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(SECRET, "<redacted>")
            .replace(QUERY, "?<redacted>")
            .replace('\n', ' ')
            .take(120)
    }

    private val SECRET = Regex("(?i)\\b(?:sk|xi|pk)[-_][A-Za-z0-9_\\-*.]{6,}|(?<=bearer )[A-Za-z0-9_\\-.=]{8,}")
    private val QUERY = Regex("\\?[^\\s\"']+")

    private const val IDEA_TOKEN = "github_idea_token"
}

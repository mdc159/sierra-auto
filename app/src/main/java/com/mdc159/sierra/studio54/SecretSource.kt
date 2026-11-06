package com.mdc159.sierra.studio54

/**
 * Where the app gets its keys when it is signed in to Studio 54. Values are held in memory
 * and in an encrypted on-device cache; callers must never log or display them.
 */
interface SecretSource {
    /** The value of the secret called [name], or null when it is not known. */
    fun get(name: String): String?

    /** The names of every secret currently available, sorted. Never the values. */
    fun names(): Set<String>

    /** When the vault last answered, as epoch milliseconds, or null if it never has. */
    val lastFetched: Long?
}

/** Secret names Sierra knows how to use. The vault may hold more. */
object SecretNames {
    const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
    const val OPENAI_API_KEY = "OPENAI_API_KEY"
    const val ELEVENLABS_API_KEY = "ELEVENLABS_API_KEY"

    /** The Sierra bridge (`bridge/`): its base URL and the bearer token it checks. */
    const val SIERRA_BRIDGE_URL = "SIERRA_BRIDGE_URL"
    const val SIERRA_BRIDGE_TOKEN = "SIERRA_BRIDGE_TOKEN"
}

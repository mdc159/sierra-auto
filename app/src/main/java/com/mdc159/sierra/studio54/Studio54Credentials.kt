package com.mdc159.sierra.studio54

import java.net.URI

/**
 * The four values (plus one optional) that the computer launcher also holds. They are
 * stored encrypted on the phone and are never shown on any screen.
 *
 * Deliberately not a `data class`: a generated `toString()` would print the client secret
 * into a log or a crash report.
 */
class Studio54Credentials(
    apiUrl: String,
    val clientId: String,
    val clientSecret: String,
    val projectId: String,
    keysProjectId: String? = null,
) {
    /** Vault base URL without a trailing slash, for example `http://vault.tail1234.ts.net:8080`. */
    val apiUrl: String = apiUrl.trim().trimEnd('/')

    /** The shared keys project, or null when the phone has not been granted one. */
    val keysProjectId: String? = keysProjectId?.trim()?.takeIf { it.isNotEmpty() }

    /** Host name of the vault, shown on the import confirmation screen. */
    val vaultHost: String
        get() = hostOf(apiUrl)

    /** True when every required value is present and the URL parses. */
    val isComplete: Boolean
        get() = apiUrl.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank() &&
            projectId.isNotBlank() && hostOf(apiUrl).isNotEmpty()

    /** A description with no secret in it. */
    override fun toString(): String =
        "Studio54Credentials(host=$vaultHost, project=$projectId, keysProject=${keysProjectId ?: "none"})"

    companion object {
        const val EXTRA_API_URL = "INFISICAL_API_URL"
        const val EXTRA_CLIENT_ID = "INFISICAL_CLIENT_ID"
        const val EXTRA_CLIENT_SECRET = "INFISICAL_CLIENT_SECRET"
        const val EXTRA_PROJECT_ID = "INFISICAL_PROJECT_ID"
        const val EXTRA_KEYS_PROJECT_ID = "S54_KEYS_PROJECT_ID"

        /** Names of all extras an import may carry, in the order they are documented. */
        val EXTRAS = listOf(EXTRA_API_URL, EXTRA_CLIENT_ID, EXTRA_CLIENT_SECRET, EXTRA_PROJECT_ID, EXTRA_KEYS_PROJECT_ID)

        /**
         * Builds credentials from the string extras of an import intent. [extra] returns the
         * extra with the given name or null. Returns null if a required value is missing.
         */
        fun fromExtras(extra: (String) -> String?): Studio54Credentials? {
            val c = Studio54Credentials(
                apiUrl = extra(EXTRA_API_URL)?.trim().orEmpty(),
                clientId = extra(EXTRA_CLIENT_ID)?.trim().orEmpty(),
                clientSecret = extra(EXTRA_CLIENT_SECRET)?.trim().orEmpty(),
                projectId = extra(EXTRA_PROJECT_ID)?.trim().orEmpty(),
                keysProjectId = extra(EXTRA_KEYS_PROJECT_ID),
            )
            return c.takeIf { it.isComplete }
        }

        /** Host part of a URL, or an empty string when it does not parse. */
        fun hostOf(url: String): String =
            runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")
    }
}

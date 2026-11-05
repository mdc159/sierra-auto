package com.mdc159.sierra.update

import org.json.JSONObject

/** The newest GitHub Release of mdc159/sierra-auto, reduced to what the updater needs. */
data class ReleaseInfo(
    val tag: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    companion object {
        const val APK_NAME = "sierra-auto.apk"
        const val DOWNLOAD_PREFIX = "https://github.com/mdc159/sierra-auto/releases/download/"

        /**
         * Parses the JSON of `GET /repos/{owner}/{repo}/releases/latest`.
         * Returns null when the release has no usable tag or no [APK_NAME] asset.
         */
        fun parse(json: String): ReleaseInfo? {
            val o = JSONObject(json)
            val tag = o.optString("tag_name")
            val code = versionCodeOf(tag) ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") != APK_NAME) continue
                val url = a.optString("browser_download_url")
                // Only ever download from this repository's own release assets.
                if (!url.startsWith(DOWNLOAD_PREFIX)) return null
                return ReleaseInfo(tag, code, url, a.optLong("size", -1L), o.optString("body"))
            }
            return null
        }

        /** build.yml tags releases `v0.1.<run number>`, and that run number is the versionCode. */
        fun versionCodeOf(tag: String): Int? =
            Regex("""^v\d+\.\d+\.(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()
    }
}

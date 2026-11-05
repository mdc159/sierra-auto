package com.mdc159.sierra.update

import com.mdc159.sierra.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads the latest GitHub Release and downloads its APK. Blocking: call off the main thread. */
class GitHubReleases(
    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build(),
    private val latestUrl: String = LATEST_URL,
) {

    fun latest(): ReleaseInfo {
        val request = Request.Builder()
            .url(latestUrl)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned ${response.code}.")
            val body = response.body?.string() ?: throw IOException("Empty GitHub response.")
            return ReleaseInfo.parse(body)
                ?: throw IOException("The latest release has no ${ReleaseInfo.APK_NAME}.")
        }
    }

    /**
     * Downloads [url] into [target], replacing any earlier file. [onProgress] gets 0..1 while the
     * server reports a length, and is called from the downloading thread.
     */
    fun download(url: String, target: File, onProgress: (Float) -> Unit = {}): File {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code}).")
            val body = response.body ?: throw IOException("Empty download.")
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (partial.length() == 0L) throw IOException("Downloaded file is empty.")
        if (target.exists() && !target.delete()) throw IOException("Could not replace the old download.")
        if (!partial.renameTo(target)) throw IOException("Could not move the download into place.")
        onProgress(1f)
        return target
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/mdc159/sierra-auto/releases/latest"
    }
}

package com.mdc159.sierra.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubReleasesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun releases() = GitHubReleases(client, server.url("/repos/mdc159/sierra-auto/releases/latest").toString())

    @Test
    fun latestParsesTheReleaseAndSendsAGitHubAcceptHeader() {
        server.enqueue(MockResponse().setBody(ReleaseInfoTest.LATEST))
        val r = releases().latest()
        assertEquals(57, r.versionCode)
        val request = server.takeRequest()
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent")!!.contains("SierraAuto"))
    }

    @Test
    fun latestReportsHttpErrors() {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            releases().latest()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    @Test
    fun latestWithoutApkIsAnError() {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.1.9","assets":[]}"""))
        try {
            releases().latest()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains(ReleaseInfo.APK_NAME))
        }
    }

    @Test
    fun downloadWritesTheFileReplacesOldOneAndReportsProgress() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val target = File(folder.newFolder("updates"), ReleaseInfo.APK_NAME)
        target.writeText("stale")
        val progress = mutableListOf<Float>()

        val out = releases().download(server.url("/sierra-auto.apk").toString(), target) { progress += it }

        assertEquals(target, out)
        assertArrayEquals(bytes, out.readBytes())
        assertFalse(File(target.parentFile, target.name + ".part").exists())
        assertEquals(1f, progress.last())
        assertTrue(progress.all { it in 0f..1f })
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun downloadFailureKeepsTheOldFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        val target = File(folder.newFolder("updates"), ReleaseInfo.APK_NAME)
        target.writeText("keep me")
        try {
            releases().download(server.url("/missing.apk").toString(), target)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals("keep me", target.readText())
    }
}

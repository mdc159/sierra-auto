package com.mdc159.sierra.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    @Test
    fun parsesTagVersionCodeAndApkAsset() {
        val r = ReleaseInfo.parse(LATEST)!!
        assertEquals("v0.1.57", r.tag)
        assertEquals(57, r.versionCode)
        assertEquals("https://github.com/mdc159/sierra-auto/releases/download/v0.1.57/sierra-auto.apk", r.apkUrl)
        assertEquals(4_321_000L, r.apkSize)
        assertTrue(r.notes.startsWith("Signed build"))
    }

    @Test
    fun ignoresOtherAssets() {
        val json = LATEST.replace("\"name\": \"sierra-auto.apk\"", "\"name\": \"mapping.txt\"")
        assertNull(ReleaseInfo.parse(json))
    }

    @Test
    fun rejectsTagsWithoutRunNumber() {
        assertNull(ReleaseInfo.versionCodeOf("latest"))
        assertNull(ReleaseInfo.versionCodeOf("v0.1"))
        assertNull(ReleaseInfo.versionCodeOf(""))
        assertEquals(7, ReleaseInfo.versionCodeOf("v0.1.7"))
        assertEquals(100042, ReleaseInfo.versionCodeOf(" v2.0.100042 "))
        assertNull(ReleaseInfo.parse(LATEST.replace("v0.1.57", "nightly")))
    }

    @Test
    fun missingAssetsArrayOrUrlIsNotARelease() {
        assertNull(ReleaseInfo.parse("""{"tag_name":"v0.1.3"}"""))
        assertNull(ReleaseInfo.parse("""{"tag_name":"v0.1.3","assets":[{"name":"sierra-auto.apk"}]}"""))
    }

    @Test
    fun comparesAgainstInstalledVersionCode() {
        val r = ReleaseInfo.parse(LATEST)!!
        assertTrue(r.isNewerThan(56))
        assertFalse(r.isNewerThan(57))
        // A Play build uses 100000 + run number, so GitHub never looks newer than it.
        assertFalse(r.isNewerThan(100_012))
    }

    companion object {
        val LATEST = """
            {
              "tag_name": "v0.1.57",
              "name": "Sierra v0.1.57",
              "body": "Signed build of abc1234 (versionCode 57). Install sierra-auto.apk on the phone.",
              "assets": [
                {"name": "signer.txt", "size": 300,
                 "browser_download_url": "https://github.com/mdc159/sierra-auto/releases/download/v0.1.57/signer.txt"},
                {"name": "sierra-auto.apk", "size": 4321000,
                 "browser_download_url": "https://github.com/mdc159/sierra-auto/releases/download/v0.1.57/sierra-auto.apk"}
              ]
            }
        """.trimIndent()
    }
}

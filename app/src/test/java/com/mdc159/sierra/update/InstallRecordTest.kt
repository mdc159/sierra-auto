package com.mdc159.sierra.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRecordTest {

    private fun record(installing: String?, initiating: String?) = InstallRecord(installing, initiating, null, null)

    @Test
    fun playInstallsLookLikePlay() {
        assertTrue(record("com.android.vending", "com.android.vending").looksLikePlay)
        // UnknownAuto / KingInstaller result: the system installer initiated, Play is installer of record.
        assertTrue(record("com.android.vending", "com.google.android.packageinstaller").looksLikePlay)
        assertTrue(record("com.android.vending", "com.samsung.android.packageinstaller").looksLikePlay)
    }

    @Test
    fun adbInstallWithPlayAsInstallerDoesNotLookLikePlay() {
        val adb = record("com.android.vending", "com.android.shell")
        assertFalse(adb.looksLikePlay)
        assertTrue(adb.looksLikeAdb)
        assertTrue(adb.verdict().contains("adb"))
    }

    @Test
    fun selfUpdateAndPlainSideloadDoNotLookLikePlay() {
        assertFalse(record("com.mdc159.sierra", "com.mdc159.sierra").looksLikePlay)
        assertFalse(record("com.google.android.packageinstaller", "com.google.android.packageinstaller").looksLikePlay)
        assertFalse(record(null, null).looksLikePlay)
        assertFalse(record(null, "com.android.shell").looksLikePlay)
        assertFalse(record(null, null).looksLikeAdb)
    }

    @Test
    fun summaryNamesEveryField() {
        val s = InstallRecord("com.android.vending", "com.google.android.packageinstaller", 2, "com.android.vending").summary()
        assertTrue(s.contains("Installer of record: com.android.vending"))
        assertTrue(s.contains("Initiating package: com.google.android.packageinstaller"))
        assertTrue(s.contains("Package source: store (2)"))
        assertTrue(s.contains("Update owner: com.android.vending"))
        val bare = InstallRecord(null, null, null, null).summary()
        assertTrue(bare.contains("Installer of record: none"))
        assertFalse(bare.contains("Update owner"))
    }

    @Test
    fun packageSourceNames() {
        assertEquals("unknown", InstallRecord.sourceName(null))
        assertEquals("unspecified (0)", InstallRecord.sourceName(0))
        assertEquals("downloaded file (4)", InstallRecord.sourceName(4))
        assertEquals("code 9", InstallRecord.sourceName(9))
    }
}

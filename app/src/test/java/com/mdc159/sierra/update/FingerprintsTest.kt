package com.mdc159.sierra.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintsTest {

    @Test
    fun sha256MatchesApksignerFormat() {
        // SHA-256("abc"), printed the way `apksigner verify --print-certs` prints certificate digests.
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            Fingerprints.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun sameSignerRequiresIdenticalNonEmptySets() {
        val release = setOf(Fingerprints.sha256(byteArrayOf(1, 2, 3)))
        val debug = setOf(Fingerprints.sha256(byteArrayOf(9, 9, 9)))
        assertTrue(Fingerprints.sameSigner(release, release.toSet()))
        assertFalse(Fingerprints.sameSigner(release, debug))
        assertFalse(Fingerprints.sameSigner(release, release + debug))
        assertFalse(Fingerprints.sameSigner(emptySet(), emptySet()))
        assertFalse(Fingerprints.sameSigner(release, emptySet()))
    }
}

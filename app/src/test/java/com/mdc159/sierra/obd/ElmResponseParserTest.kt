package com.mdc159.sierra.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElmResponseParserTest {
    private fun hex(s: String) = ElmResponseParser.hexToBytes(s.replace(" ", ""))

    @Test
    fun linesDropEchoBlanksAndSearching() {
        val lines = ElmResponseParser.lines("010C\r\rSEARCHING...\r410C0BB8\r\r", "010C")
        assertEquals(listOf("410C0BB8"), lines)
    }

    @Test
    fun searchingOnSameLineAsData() {
        assertEquals(listOf("410C0BB8"), ElmResponseParser.lines("SEARCHING...410C0BB8\r"))
    }

    @Test
    fun errorDetection() {
        assertEquals("NO DATA", ElmResponseParser.errorIn(listOf("NO DATA")))
        assertEquals("?", ElmResponseParser.errorIn(listOf("?")))
        assertEquals("UNABLE TO CONNECT", ElmResponseParser.errorIn(listOf("UNABLE TO CONNECT")))
        assertEquals("empty reply", ElmResponseParser.errorIn(emptyList()))
        assertNull(ElmResponseParser.errorIn(listOf("410C0BB8")))
        assertNull(ElmResponseParser.errorIn(listOf("014", "0:490201314743")))
    }

    @Test
    fun singleFrameMessages() {
        val msgs = ElmResponseParser.messages(listOf("410C0BB8", "410C0000"))
        assertEquals(2, msgs.size)
        assertArrayEquals(hex("41 0C 0B B8"), msgs[0])
    }

    @Test
    fun multiFrameReassemblyTrimsToLength() {
        val lines = listOf(
            "014",
            "0:490201314743",
            "1:5431324533374A",
            "2:35393337313131",
        )
        val msgs = ElmResponseParser.messages(lines)
        assertEquals(1, msgs.size)
        assertEquals(0x14, msgs[0].size)
        assertArrayEquals(hex("49 02 01 31 47 43 54 31 32 45 33 37 4A 35 39 33 37 31 31 31"), msgs[0])
    }

    @Test
    fun multiFrameFollowedBySingleFrame() {
        val lines = listOf("007", "0:430204200171", "410D00")
        val msgs = ElmResponseParser.messages(lines)
        assertEquals(2, msgs.size)
        assertArrayEquals(hex("43 02 04 20 01 71"), msgs[0])
        assertArrayEquals(hex("41 0D 00"), msgs[1])
    }

    @Test
    fun spacedHexIsAccepted() {
        assertArrayEquals(hex("41 0C 0B B8"), ElmResponseParser.messages(listOf("41 0C 0B B8"))[0])
    }

    @Test
    fun oddFragmentsAreSkipped() {
        assertEquals(0, ElmResponseParser.messages(listOf("41 0C 0")).size)
    }
}

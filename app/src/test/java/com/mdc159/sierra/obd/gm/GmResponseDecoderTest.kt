package com.mdc159.sierra.obd.gm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmResponseDecoderTest {
    private val trans = GmDids.TRANS_FLUID_TEMP

    private fun value(reading: GmReading<*>): GmReading.Value<*> {
        assertTrue("expected a value, got $reading", reading is GmReading.Value)
        return reading as GmReading.Value<*>
    }

    private fun negative(reading: GmReading<*>): GmReading.NegativeResponse {
        assertTrue("expected a negative response, got $reading", reading is GmReading.NegativeResponse)
        return reading as GmReading.NegativeResponse
    }

    private fun noReply(reading: GmReading<*>): GmReading.NoReply {
        assertTrue("expected no reply, got $reading", reading is GmReading.NoReply)
        return reading as GmReading.NoReply
    }

    private fun malformed(reading: GmReading<*>): GmReading.Malformed {
        assertTrue("expected malformed, got $reading", reading is GmReading.Malformed)
        return reading as GmReading.Malformed
    }

    @Test
    fun positiveSingleFrame() {
        val v = value(GmResponseDecoder.decode(trans, "62194085"))
        assertEquals(93.0, v.value as Double, 0.001)
        assertEquals(GmModule.TCM, v.module)
        assertEquals(listOf(0x85), v.raw)
        assertEquals(93.0, GmResponseDecoder.decode(trans, "62194085").valueOrNull() as Double, 0.001)
    }

    @Test
    fun echoSearchingAndBlankLinesAreTolerated() {
        val v = value(GmResponseDecoder.decode(trans, "221940\r\rSEARCHING...\r62194085\r\r"))
        assertEquals(93.0, v.value as Double, 0.001)
    }

    @Test
    fun spacedHexIsAccepted() {
        assertEquals(93.0, value(GmResponseDecoder.decode(trans, "62 19 40 85")).value as Double, 0.001)
    }

    @Test
    fun firstMatchingModuleWinsWhenSeveralAnswer() {
        assertEquals(93.0, value(GmResponseDecoder.decode(trans, "62194085\r62194090")).value as Double, 0.001)
    }

    @Test
    fun multiFrameLayoutIsReassembled() {
        val v = value(GmResponseDecoder.decode(trans, "008\r0:621940850102\r1:0304"))
        assertEquals(93.0, v.value as Double, 0.001)
        assertEquals(listOf(0x85, 0x01, 0x02, 0x03, 0x04), v.raw)
    }

    @Test
    fun requestOutOfRangeIsANegativeResponseThatSuggestsAnotherModule() {
        val n = negative(GmResponseDecoder.decode(trans, "7F2231"))
        assertEquals(0x31, n.nrc)
        assertTrue(n.meaning.startsWith("requestOutOfRange"))
        assertTrue(n.suggestsWrongModule)
        assertEquals(GmModule.TCM, n.module)
        assertNull(GmResponseDecoder.decode(trans, "7F2231").valueOrNull())
    }

    @Test
    fun serviceNotSupportedIsNegativeAndSuggestsAnotherModule() {
        val n = negative(GmResponseDecoder.decode(trans, "7F 22 11"))
        assertEquals(0x11, n.nrc)
        assertTrue(n.suggestsWrongModule)
    }

    @Test
    fun conditionsNotCorrectDoesNotSuggestAnotherModule() {
        val n = negative(GmResponseDecoder.decode(trans, "7F2222"))
        assertEquals(0x22, n.nrc)
        assertFalse(n.suggestsWrongModule)
        assertTrue(n.meaning.startsWith("conditionsNotCorrect"))
    }

    @Test
    fun unknownNegativeCodeStillDescribes() {
        val n = negative(GmResponseDecoder.decode(trans, "7F22E5"))
        assertEquals(0xE5, n.nrc)
        assertTrue(n.meaning.contains("0xE5"))
    }

    @Test
    fun responsePendingFollowedByValue() {
        assertEquals(93.0, value(GmResponseDecoder.decode(trans, "7F2278\r62194085")).value as Double, 0.001)
    }

    @Test
    fun responsePendingWithNoFinalReplyIsNoReply() {
        assertTrue(noReply(GmResponseDecoder.decode(trans, "7F2278")).reason.contains("pending"))
    }

    @Test
    fun negativeForAnotherServiceIsNotOurs() {
        malformed(GmResponseDecoder.decode(trans, "7F0131"))
    }

    @Test
    fun adapterErrorsAreNoReply() {
        assertEquals("NO DATA", noReply(GmResponseDecoder.decode(trans, "NO DATA")).reason)
        assertEquals("?", noReply(GmResponseDecoder.decode(trans, "?")).reason)
        assertEquals("CAN ERROR", noReply(GmResponseDecoder.decode(trans, "CAN ERROR")).reason)
        assertEquals("empty reply", noReply(GmResponseDecoder.decode(trans, "")).reason)
        assertEquals("empty reply", noReply(GmResponseDecoder.decode(trans, "221940\r")).reason)
    }

    @Test
    fun shortFrameWithoutDataByteIsMalformed() {
        assertTrue(malformed(GmResponseDecoder.decode(trans, "621940")).reason.contains("short frame"))
    }

    @Test
    fun shortFrameWithTruncatedEchoIsMalformed() {
        assertTrue(malformed(GmResponseDecoder.decode(trans, "6219")).reason.contains("short frame"))
    }

    @Test
    fun wrongDidEchoIsMalformed() {
        val m = malformed(GmResponseDecoder.decode(trans, "62115485"))
        assertTrue(m.reason, m.reason.contains("0x1154") && m.reason.contains("0x1940"))
    }

    @Test
    fun unrelatedPositiveReplyIsMalformed() {
        assertTrue(malformed(GmResponseDecoder.decode(trans, "410C0BB8")).reason.contains("no 62 reply"))
    }

    @Test
    fun moduleOverrideIsCarriedIntoTheReading() {
        assertEquals(GmModule.ECM, GmResponseDecoder.decode(trans, "62194085", GmModule.ECM).module)
        assertEquals(GmModule.ECM, GmResponseDecoder.decode(trans, "NO DATA", GmModule.ECM).module)
    }

    @Test
    fun everyDidDecodesThroughTheDecoder() {
        assertEquals(100.0, value(GmResponseDecoder.decode(GmDids.ENGINE_OIL_TEMP, "6211548C")).value as Double, 0.001)
        assertEquals(100.0, value(GmResponseDecoder.decode(GmDids.ENGINE_OIL_PRESSURE, "62147019")).value as Double, 0.001)
        assertEquals(204.0, value(GmResponseDecoder.decode(GmDids.OIL_PRESSURE_SENSOR, "62115C33")).value as Double, 0.001)
        assertEquals(100.0, value(GmResponseDecoder.decode(GmDids.OIL_LIFE, "62119FFF")).value as Double, 0.001)
        assertEquals(4, value(GmResponseDecoder.decode(GmDids.CURRENT_GEAR, "62199A04")).value)
        for (did in GmDids.ALL) {
            malformed(GmResponseDecoder.decode(did, "62" + did.request.substring(2)))
            assertEquals(0x31, negative(GmResponseDecoder.decode(did, "7F2231")).nrc)
        }
    }
}

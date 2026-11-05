package com.mdc159.sierra.obd.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.concurrent.thread

class ByteInboxTest {

    @Test
    fun readsAcrossChunkBoundaries() {
        val inbox = ByteInbox()
        inbox.offer("410C".toByteArray())
        inbox.offer("0BB8\r>".toByteArray())
        val buf = ByteArray(5)
        assertEquals(5, inbox.read(buf, 0))
        assertEquals("410C0", String(buf))
        assertEquals(5, inbox.available())
        val rest = ByteArray(32)
        val n = inbox.read(rest, 0)
        assertEquals("BB8\r>", String(rest, 0, n))
        assertEquals(0, inbox.read(rest, 0))
    }

    @Test
    fun timesOutWithZeroAndWakesOnOffer() {
        val inbox = ByteInbox()
        val buf = ByteArray(8)
        val started = System.nanoTime()
        assertEquals(0, inbox.read(buf, 50))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 40)

        thread {
            Thread.sleep(30)
            inbox.offer(byteArrayOf('>'.code.toByte()))
        }
        assertEquals(1, inbox.read(buf, 5_000))
        assertEquals('>'.code.toByte(), buf[0])
    }

    @Test
    fun closeDrainsThenReportsEndOfStream() {
        val inbox = ByteInbox()
        inbox.offer("OK".toByteArray())
        inbox.close()
        val buf = ByteArray(8)
        assertEquals(2, inbox.read(buf, 0))
        assertEquals(-1, inbox.read(buf, 0))
        inbox.offer("late".toByteArray())
        assertEquals(-1, inbox.read(buf, 0))
    }

    @Test
    fun framingSplitsToPayload() {
        val chunks = BleFraming.chunks("ATZ\r".toByteArray(), 20)
        assertEquals(1, chunks.size)
        val long = ByteArray(45) { it.toByte() }
        val split = BleFraming.chunks(long, 20)
        assertEquals(listOf(20, 20, 5), split.map { it.size })
        assertEquals(long.toList(), split.flatMap { it.toList() })
        assertEquals(20, BleFraming.payloadFor(23))
        assertEquals(244, BleFraming.payloadFor(247))
        assertEquals(0, BleFraming.chunks(ByteArray(0), 20).size)
    }

    @Test
    fun layoutPickerMatchesServiceAndBothCharacteristics() {
        val nus = BleUartLayouts.CANDIDATES.first()
        val discovered = mapOf(
            UUID.fromString("00001800-0000-1000-8000-00805F9B34FB") to listOf(UUID.randomUUID()),
            nus.service to listOf(nus.write, nus.notify),
        )
        assertEquals(nus, BleUartLayouts.pick(discovered))
        // Service present but the notify characteristic missing: no match.
        assertEquals(null, BleUartLayouts.pick(mapOf(nus.service to listOf(nus.write))))
        assertEquals(null, BleUartLayouts.pick(emptyMap()))
        // Every candidate is self-consistent and distinct.
        assertEquals(BleUartLayouts.CANDIDATES.size, BleUartLayouts.CANDIDATES.map { it.service }.toSet().size)
        BleUartLayouts.CANDIDATES.forEach { assertTrue(it.name.contains("unverified")) }
    }
}

package com.mdc159.sierra.voice.duplex

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DuplexLifecycleTest {
    private class Session : DuplexSession {
        override val provider = DuplexProvider.OPENAI_REALTIME
        override val inputSampleRate = 24000
        override val outputSampleRate = 24000
        val closes = AtomicInteger()
        var onStart: () -> Unit = {}
        override fun start(listener: DuplexListener) { onStart() }
        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {}
        override fun interrupt(playedMs: Long) {}
        override fun sendText(text: String) {}
        override fun close() { closes.incrementAndGet() }
    }
    private class Mic(val fail: Boolean = false) : MicSource {
        override val sampleRate = 16000
        val opens = AtomicInteger()
        override fun start() { if (fail) throw DuplexException("mic refused"); opens.incrementAndGet() }
        override fun read(dest: ByteArray): Int = -1
        override fun stop() { opens.set(0) }
    }
    private class Sink : PcmSink {
        val opens = AtomicInteger()
        override fun start(sampleRate: Int) { opens.incrementAndGet() }
        override fun write(pcm: ByteArray, length: Int) {}
        override fun discard() {}
        override fun stop() { opens.set(0) }
    }
    private class Focus : FocusHandle {
        override fun request(onLoss: () -> Unit) = true
        override fun abandon() {}
    }
    @Test fun micStartFailureMustCloseConnectedSession() {
        val session = Session()
        val controller = DuplexController(session, Mic(true), Sink(), Focus(), DuplexMode.FULL_DUPLEX, listener = {})
        try { controller.start(); fail("expected mic failure") } catch (_: DuplexException) {}
        assertEquals("Connected websocket must be closed after microphone startup fails", 1, session.closes.get())
        assertFalse("Controller must not report running after failed start", controller.isRunning)
    }
    @Test fun stopDuringHandshakeMustNotReopenAudio() {
        val session = Session()
        val mic = Mic()
        val sink = Sink()
        val handshakeEntered = CountDownLatch(1)
        val handshakeReturn = CountDownLatch(1)
        session.onStart = { handshakeEntered.countDown(); check(handshakeReturn.await(2, TimeUnit.SECONDS)) }
        val controller = DuplexController(session, mic, sink, Focus(), DuplexMode.FULL_DUPLEX, listener = {})
        val startThread = Thread { controller.start() }
        startThread.start()
        assertTrue(handshakeEntered.await(2, TimeUnit.SECONDS))
        controller.stop()
        handshakeReturn.countDown()
        startThread.join(2000)
        controller.stop()
        assertEquals("Microphone reopened after stop and a second stop cannot release it", 0, mic.opens.get())
        assertEquals(0, sink.opens.get())
    }
}


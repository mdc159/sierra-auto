package com.mdc159.sierra.voice.duplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A session that records what the controller sends and lets the test inject events. */
private class FakeSession(
    override val inputSampleRate: Int = 24_000,
    override val outputSampleRate: Int = 24_000,
) : DuplexSession {
    override val provider = DuplexProvider.OPENAI_REALTIME
    lateinit var listener: DuplexListener
    val audio = LinkedBlockingQueue<ByteArray>()
    val interrupts = LinkedBlockingQueue<Long>()
    val closed = CountDownLatch(1)
    var failStart: String? = null

    override fun start(listener: DuplexListener) {
        failStart?.let { throw DuplexException(it) }
        this.listener = listener
        listener.onEvent(DuplexEvent.Connected(provider))
    }

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
        audio.put(pcm.copyOfRange(offset, offset + length))
    }

    override fun interrupt(playedMs: Long) {
        interrupts.put(playedMs)
    }

    override fun sendText(text: String) {}

    override fun close() {
        closed.countDown()
        if (::listener.isInitialized) listener.onEvent(DuplexEvent.Ended("Ended"))
    }
}

/** Feeds the given frames, then blocks until told to end (read returns -1). */
private class FakeMic(override val sampleRate: Int = 16_000) : MicSource {
    val frames = LinkedBlockingQueue<ByteArray>()
    val started = AtomicInteger()
    val stopped = AtomicInteger()
    private val END = ByteArray(0)

    override fun start() {
        started.incrementAndGet()
    }

    override fun read(dest: ByteArray): Int {
        val f = frames.poll(2, TimeUnit.SECONDS) ?: return 0
        if (f === END) return -1
        System.arraycopy(f, 0, dest, 0, minOf(f.size, dest.size))
        return minOf(f.size, dest.size)
    }

    fun end() = frames.put(END)

    override fun stop() {
        stopped.incrementAndGet()
    }
}

private class FakeSink : PcmSink {
    var rate = 0
    val written = CopyOnWriteArrayList<ByteArray>()
    val discards = AtomicInteger()
    val stops = AtomicInteger()
    val firstWrite = CountDownLatch(1)

    override fun start(sampleRate: Int) {
        rate = sampleRate
    }

    override fun write(pcm: ByteArray, length: Int) {
        written += pcm.copyOf(length)
        firstWrite.countDown()
    }

    override fun discard() {
        discards.incrementAndGet()
    }

    override fun stop() {
        stops.incrementAndGet()
    }
}

private class FakeFocus(private val grant: Boolean = true) : FocusHandle {
    var onLoss: (() -> Unit)? = null
    val abandons = AtomicInteger()

    override fun request(onLoss: () -> Unit): Boolean {
        this.onLoss = onLoss
        return grant
    }

    override fun abandon() {
        abandons.incrementAndGet()
    }
}

class DuplexControllerTest {

    @Test fun fatalProviderErrorsDoNotEnterTheMicLog() {
        val logs = CopyOnWriteArrayList<String>()
        val c = DuplexController(session, mic, sink, focus, DuplexMode.FULL_DUPLEX, listener = events, log = { logs += it })
        try {
            c.start()
            session.listener.onEvent(DuplexEvent.Error("private-request sk-private-token", fatal = true))
            assertTrue(logs.contains("duplex stopped reason=voice service error"))
            assertFalse(logs.joinToString().contains("private-request"))
            assertFalse(logs.joinToString().contains("sk-private"))
        } finally { c.stop(); mic.end() }
    }

    private val session = FakeSession()
    private val mic = FakeMic()
    private val sink = FakeSink()
    private val focus = FakeFocus()
    private val events = EventSink()

    private fun controller(mode: DuplexMode = DuplexMode.FULL_DUPLEX) = DuplexController(
        session = session,
        mic = mic,
        sink = sink,
        focus = focus,
        mode = mode,
        listener = events,
        frameMs = 40,
        speakerRate = 16_000,
        jitterPrefillMs = 0,
    )

    @Test
    fun micFramesAreResampledToTheServiceRate() {
        val c = controller()
        c.start()
        events.await<DuplexEvent.Connected>()
        assertEquals(1, mic.started.get())
        mic.frames.put(ByteArray(1280) { 1 }) // 40 ms at 16 kHz
        val sent = session.audio.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("no audio sent")
        assertEquals(1920.0, sent.size.toDouble(), 4.0) // 40 ms at 24 kHz
        c.stop()
    }

    @Test
    fun assistantAudioIsResampledToTheSpeakerAndPlayed() {
        val c = controller()
        c.start()
        assertEquals(16_000, sink.rate)
        session.listener.onEvent(DuplexEvent.AssistantAudio(ByteArray(4800) { 3 }, 24_000, "i1")) // 100 ms
        session.listener.onEvent(DuplexEvent.AssistantDone(cancelled = false))
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        val total = sink.written.sumOf { it.size }
        assertEquals(3200.0, total.toDouble(), 64.0) // 100 ms at 16 kHz
        c.stop()
    }

    @Test
    fun speechStartedCutsPlaybackAndReportsPlayedMs() {
        val c = controller()
        c.start()
        session.listener.onEvent(DuplexEvent.AssistantAudio(ByteArray(48_000), 24_000, "i1")) // 1 s
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        session.listener.onEvent(DuplexEvent.UserStartedSpeaking)
        val played = session.interrupts.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("no interrupt")
        assertTrue("played $played", played in 0..1000)
        assertEquals(1, sink.discards.get())
        events.await<DuplexEvent.UserStartedSpeaking>()
        c.stop()
    }

    @Test
    fun tapInterruptTellsTheSession() {
        val c = controller()
        c.start()
        c.tapInterrupt()
        assertEquals(0L, session.interrupts.poll(2, TimeUnit.SECONDS))
        assertEquals(1, sink.discards.get())
        c.stop()
    }

    @Test
    fun guardedModeGatesTheMicWhileTheAssistantPlays() {
        val c = controller(DuplexMode.GUARDED)
        c.start()
        // Loud playback at the speaker.
        val loud = ByteArray(4800) { i -> if (i % 2 == 0) 0x10 else 0x27 } // samples 0x2710 = 10000
        session.listener.onEvent(DuplexEvent.AssistantAudio(loud, 24_000, "i1"))
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        // A quiet mic frame right after: gated to silence.
        mic.frames.put(ByteArray(1280) { i -> if (i % 2 == 0) 0x64 else 0 }) // samples = 100
        val sent = session.audio.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("no audio sent")
        assertTrue(sent.all { it == 0.toByte() })
        assertTrue(c.gate.isGated)
        c.stop()
    }

    @Test
    fun micClosingEndsEverythingAndReleasesFocus() {
        val c = controller()
        c.start()
        mic.end()
        val ended = events.await<DuplexEvent.Ended>()
        assertEquals("Microphone closed", ended.reason)
        assertTrue(session.closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, focus.abandons.get())
        assertEquals(1, mic.stopped.get())
        assertEquals(1, sink.stops.get())
        Thread.sleep(100)
        assertEquals(1, events.all<DuplexEvent.Ended>().size)
        assertFalse(c.isRunning)
    }

    @Test
    fun focusLossStopsTheSession() {
        val c = controller()
        c.start()
        focus.onLoss!!.invoke()
        assertEquals("Audio focus lost", events.await<DuplexEvent.Ended>().reason)
    }

    @Test
    fun refusedFocusFailsStartWithoutTouchingTheMic() {
        val c = DuplexController(session, mic, sink, FakeFocus(grant = false), DuplexMode.GUARDED, listener = events)
        try {
            c.start()
            fail("expected failure")
        } catch (e: DuplexException) {
            assertEquals("Couldn't get the audio. Try again.", e.message)
        }
        assertEquals(0, mic.started.get())
    }

    @Test
    fun sessionStartFailureCleansUp() {
        session.failStart = "nope"
        val c = controller()
        try {
            c.start()
            fail("expected failure")
        } catch (e: DuplexException) {
            assertEquals("nope", e.message)
        }
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun sessionEndingForwardsEndedOnce() {
        val c = controller()
        c.start()
        session.listener.onEvent(DuplexEvent.Ended("The voice service ended the conversation."))
        assertEquals("The voice service ended the conversation.", events.await<DuplexEvent.Ended>().reason)
        c.stop()
        Thread.sleep(100)
        assertEquals(1, events.all<DuplexEvent.Ended>().size)
        assertEquals(1, focus.abandons.get())
    }
}

package com.mdc159.sierra.voice.duplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** n ms of 16-bit mono silence at [rate]. */
private fun silence(ms: Long, rate: Int): ByteArray = ByteArray(Resampler.bytesForMs(ms, rate))

/** n ms of a tone at [rate], so the echo gate sees a real level. */
private fun tone(ms: Long, rate: Int, amplitude: Int = 8_000): ByteArray {
    val samples = (rate * ms / 1000).toInt()
    return ByteArray(samples * 2) { i ->
        val s = (amplitude * sin(2 * PI * 300 * (i / 2) / rate)).toInt()
        if (i % 2 == 0) (s and 0xFF).toByte() else ((s shr 8) and 0xFF).toByte()
    }
}

/**
 * 163ad2b + b8bd973: playback waits for a prefill before a reply starts, and after an
 * underrun (the network delivering slower than real time) it waits longer than last
 * time before resuming, instead of playing each late delta as its own short burst.
 */
class JitterBufferPrefillTest {

    /** b8bd973: the shipped defaults are a 700 ms prefill (two deltas), step 400 ms, max 2 s. */
    @Test
    fun defaultsWaitFor700msBeforePlaying() {
        val jb = JitterBuffer(24_000)
        assertEquals(700, jb.prefillMs)
        assertEquals(2_000, jb.maxPrefillMs)
        assertEquals(400, jb.stepMs)

        jb.offer(silence(699, 24_000)) // one delta is about 380 ms: not two yet
        assertEquals("played before the 700 ms prefill", 0, jb.read(ByteArray(96_000), timeoutMs = 60))
        jb.offer(silence(50, 24_000))
        assertTrue(jb.read(ByteArray(96_000), timeoutMs = 200) > 0)
    }

    @Test
    fun underrunsGrowTheWaitByTheStepUpToTheMax() {
        val jb = JitterBuffer(16_000, prefillMs = 100, maxPrefillMs = 2_000, stepMs = 400)

        jb.offer(silence(100, 16_000))
        assertEquals(bytes(100), drain(jb))
        assertEquals(1, jb.underruns)
        assertEquals(500, jb.targetMs) // 100 + 400

        // 100 ms alone no longer starts playback: it must rebuild 500 ms first.
        jb.offer(silence(100, 16_000))
        assertEquals(0, jb.read(ByteArray(3200), timeoutMs = 60))
        jb.offer(silence(400, 16_000))
        assertEquals(bytes(500), drain(jb))
        assertEquals(900, jb.targetMs)

        jb.offer(silence(900, 16_000))
        assertEquals(bytes(900), drain(jb))
        assertEquals(1300, jb.targetMs)

        jb.offer(silence(1300, 16_000))
        assertEquals(bytes(1300), drain(jb))
        assertEquals(1700, jb.targetMs)

        jb.offer(silence(1700, 16_000))
        assertEquals(bytes(1700), drain(jb))
        assertEquals(2_000, jb.targetMs) // capped

        jb.offer(silence(2000, 16_000))
        assertEquals(bytes(2000), drain(jb))
        assertEquals(2_000, jb.targetMs)
        assertEquals(6, jb.underruns)
    }

    /** 163ad2b: a reply that ends (flush) resets the target, so the next reply starts after the prefill only. */
    @Test
    fun aReplyThatEndsResetsTheTargetToThePrefill() {
        val jb = JitterBuffer(16_000, prefillMs = 100, maxPrefillMs = 2_000, stepMs = 400)
        jb.offer(silence(100, 16_000))
        drain(jb)
        assertEquals(500, jb.targetMs) // grew after the underrun

        jb.offer(silence(100, 16_000))
        jb.flush() // this reply is done: play what is left without waiting
        assertEquals(bytes(100), drain(jb))
        assertEquals(100, jb.targetMs)
        assertEquals(1, jb.underruns) // the flush is not an underrun

        jb.offer(silence(100, 16_000))
        assertTrue(jb.read(ByteArray(3200), timeoutMs = 60) > 0) // plays after the prefill only
    }

    private fun bytes(ms: Long) = Resampler.bytesForMs(ms, 16_000).toLong()

    /** Reads until the buffer runs dry; returns the byte count. */
    private fun drain(jb: JitterBuffer): Long {
        var total = 0L
        val dest = ByteArray(1 shl 19)
        while (true) {
            val n = jb.read(dest, timeoutMs = 100)
            if (n <= 0) return total
            total += n
        }
    }
}

/**
 * 9ec487a: on the phone's loudspeaker the platform echo canceller needs a few seconds of
 * the assistant's voice to converge, so full duplex guards the mic for the first 6 s of a
 * session's assistant audio (DuplexSettingsActivity.PHONE_FULL_DUPLEX_WARMUP_MS), then
 * passes everything.
 */
class EchoGateWarmupTest {

    private var now = 10_000L
    private val loud = tone(40, 16_000) // the driver, RMS about 5657
    private val quiet = tone(40, 16_000, amplitude = 1_000) // echo level, RMS about 700
    private val playback = tone(20, 16_000, amplitude = 2_000) // RMS about 1414

    private fun gate(warmupMs: Long = 6_000) =
        EchoGate(DuplexMode.FULL_DUPLEX, ratio = 2.0, hangoverMs = 250, floorRms = 400.0, clock = { now }, fullWarmupMs = warmupMs)

    private fun play(g: EchoGate, ms: Long) = repeat((ms / 20).toInt()) { g.onPlayback(playback, durationMs = 20) }

    @Test
    fun theMicIsGatedDuringTheFirstSixSecondsOfAssistantAudio() {
        val g = gate()
        assertTrue(g.inWarmup)
        play(g, 5_980)
        assertTrue(g.inWarmup)

        val out = g.process(quiet) // echo-level mic while the assistant plays
        assertTrue("echo leaked through during the warm-up", g.isGated)
        assertTrue(out.all { it == 0.toByte() })

        // The driver can still talk over it by being louder.
        assertEquals(loud, g.process(loud))
        assertFalse(g.isGated)
    }

    @Test
    fun theMicOpensOnceSixSecondsHavePlayed() {
        val g = gate()
        play(g, 6_000)
        assertFalse(g.inWarmup)
        g.onPlayback(playback, durationMs = 20) // still playing
        assertEquals("the mic stayed gated after the warm-up", quiet, g.process(quiet))
        assertFalse(g.isGated)
    }

    @Test
    fun noWarmupConfiguredMeansFullDuplexPassesEverything() {
        val g = gate(warmupMs = 0) // the car default: no warm-up
        assertFalse(g.inWarmup)
        g.onPlayback(playback, durationMs = 20)
        assertEquals(quiet, g.process(quiet))
        assertFalse(g.isGated)
    }

    /** The warm-up counts played audio, not wall-clock time: a silent spell keeps it on. */
    @Test
    fun theWarmupCountsPlayedAudioNotWallClock() {
        val g = gate()
        play(g, 1_000)
        now += 60_000 // a minute of nobody saying anything
        assertTrue(g.inWarmup)
    }
}

/** The controller wires the prefill and the full-duplex warm-up through to playback and the gate. */
class DuplexPlaybackWiringTest {

    private val session = PlaybackSession()
    private val mic = QueueMic()
    private val sink = RecordingSink()
    private val focus = GrantedFocus()
    private val events = EventSink()

    private fun controller(mode: DuplexMode, fullDuplexWarmupMs: Long = 0, jitterPrefillMs: Long? = null): DuplexController =
        if (jitterPrefillMs != null) {
            DuplexController(session, mic, sink, focus, mode, fullDuplexWarmupMs = fullDuplexWarmupMs, listener = events, jitterPrefillMs = jitterPrefillMs)
        } else {
            // No jitterPrefillMs: the constructor default (the shipped 700 ms) is under test.
            DuplexController(session, mic, sink, focus, mode, fullDuplexWarmupMs = fullDuplexWarmupMs, listener = events)
        }

    /** b8bd973: with the shipped defaults, playback starts only once 700 ms has queued. */
    @Test
    fun playbackWaitsForTheDefaultPrefill() {
        val c = controller(DuplexMode.FULL_DUPLEX) // no jitterPrefillMs: the default is under test
        c.start()
        events.await<DuplexEvent.Connected>()
        session.listener.onEvent(DuplexEvent.AssistantAudio(silence(600, 24_000), 24_000, "i1"))
        assertFalse("playback started before the default 700 ms prefill", sink.firstWrite.await(400, TimeUnit.MILLISECONDS))
        session.listener.onEvent(DuplexEvent.AssistantAudio(silence(200, 24_000), 24_000, "i1"))
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        c.stop()
    }

    /** 9ec487a: the controller feeds the gate the played audio, so the warm-up ends after 6 s. */
    @Test
    fun fullDuplexWarmupIsDrivenByPlayedAudio() {
        val c = controller(DuplexMode.FULL_DUPLEX, fullDuplexWarmupMs = 6_000, jitterPrefillMs = 0)
        c.start()
        events.await<DuplexEvent.Connected>()

        session.listener.onEvent(DuplexEvent.AssistantAudio(tone(2_000, 24_000), 24_000, "i1"))
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        assertTrue("warm-up already over with only 2 s played", c.gate.inWarmup)

        session.listener.onEvent(DuplexEvent.AssistantAudio(tone(6_000, 24_000), 24_000, "i2"))
        assertTrue("warm-up never ended after 8 s played", awaitTrue(5_000) { !c.gate.inWarmup })
        c.stop()
    }

    private fun awaitTrue(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            Thread.sleep(20)
        }
        return false
    }

    private class PlaybackSession : DuplexSession {
        override val provider = DuplexProvider.OPENAI_REALTIME
        override val inputSampleRate = 24_000
        override val outputSampleRate = 24_000
        lateinit var listener: DuplexListener
        override fun start(listener: DuplexListener) {
            this.listener = listener
            listener.onEvent(DuplexEvent.Connected(provider))
        }

        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {}
        override fun interrupt(cut: PlaybackCut) {}
        override fun sendText(text: String) {}
        override fun close() {}
    }

    private class QueueMic(override val sampleRate: Int = 16_000) : MicSource {
        override fun start() {}
        override fun read(dest: ByteArray): Int {
            Thread.sleep(20) // no mic frames during the playback tests; do not spin
            return 0
        }
        override fun stop() {}
    }

    private class RecordingSink : PcmSink {
        val written = CopyOnWriteArrayList<ByteArray>()
        val firstWrite = CountDownLatch(1)
        override fun start(sampleRate: Int) {}
        override fun write(pcm: ByteArray, length: Int) {
            written += pcm.copyOf(length)
            firstWrite.countDown()
        }
        override fun discard() {}
        override fun stop() {}
    }

    private class GrantedFocus : FocusHandle {
        override fun request(onLoss: () -> Unit) = true
        override fun abandon() {}
    }
}

/**
 * The item marker vs the playhead: two reply items queued (a tool preface, then its
 * answer) and the driver barges in while the first still plays. The interrupt must
 * report the audible item's played offset; the marker used to describe the latest
 * enqueued item, so the interrupt reported 0 and the service truncated the second
 * item to 0 while keeping the audible one as fully heard.
 */
class DuplexInterruptWhileASecondItemIsQueuedTest {

    private val session = RecordingSession()
    private val sink = PacedSink()
    private val events = EventSink()

    @Test
    fun bargeInWhileTheFirstOfTwoItemsPlaysReportsItsPlayedOffset() {
        val c = DuplexController(session, IdleMic(), sink, GrantedFocus(), DuplexMode.FULL_DUPLEX, listener = events, jitterPrefillMs = 0)
        c.start()
        events.await<DuplexEvent.Connected>()
        session.listener.onEvent(DuplexEvent.AssistantAudio(silence(1_000, 24_000), 24_000, "a"))
        assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
        Thread.sleep(150) // some of item "a" has played; "b" arrives while it is still audible
        session.listener.onEvent(DuplexEvent.AssistantAudio(silence(600, 24_000), 24_000, "b"))
        session.listener.onEvent(DuplexEvent.UserStartedSpeaking)
        val cut = session.cuts.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("no interrupt")
        assertEquals("the audible item was not named", "a", cut.itemId)
        assertTrue("the audible item's played offset was not reported (got ${cut.playedMs}ms)", cut.playedMs in 20..900)
        assertEquals("the queued item never played; it must be reported unheard", listOf("b"), cut.unheardItemIds)
        events.await<DuplexEvent.UserStartedSpeaking>()
        c.stop()
    }

    private class RecordingSession : DuplexSession {
        override val provider = DuplexProvider.OPENAI_REALTIME
        override val inputSampleRate = 24_000
        override val outputSampleRate = 24_000
        lateinit var listener: DuplexListener
        val cuts = LinkedBlockingQueue<PlaybackCut>()
        override fun start(listener: DuplexListener) {
            this.listener = listener
            listener.onEvent(DuplexEvent.Connected(provider))
        }
        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {}
        override fun interrupt(cut: PlaybackCut) { cuts.put(cut) }
        override fun sendText(text: String) {}
        override fun close() {}
    }

    /** Drains at real time like AudioTrack.write, so the buffer keeps a playhead mid-item. */
    private class PacedSink : PcmSink {
        val firstWrite = CountDownLatch(1)
        override fun start(sampleRate: Int) {}
        override fun write(pcm: ByteArray, length: Int) {
            firstWrite.countDown()
            Thread.sleep(20)
        }
        override fun discard() {}
        override fun stop() {}
    }

    private class IdleMic(override val sampleRate: Int = 16_000) : MicSource {
        override fun start() {}
        override fun read(dest: ByteArray): Int { Thread.sleep(20); return 0 }
        override fun stop() {}
    }

    private class GrantedFocus : FocusHandle {
        override fun request(onLoss: () -> Unit) = true
        override fun abandon() {}
    }
}

/**
 * The item boundaries against the playhead: a cut must name the item the playhead is
 * inside at its played offset, and the items after it as never heard. The marker used to
 * describe the latest enqueued item, so a cut during the first of two queued items
 * reported 0 played.
 */
class JitterBufferItemCutTest {

    @Test
    fun theCutNamesTheItemThePlayheadIsInside() {
        val jb = JitterBuffer(24_000, prefillMs = 0)
        jb.markItemStart("a")
        jb.offer(silence(1_000, 24_000))
        jb.markItemStart("b") // still buffered when the driver interrupts
        jb.offer(silence(600, 24_000))
        assertEquals(300, play(jb, 300)) // the playhead is 300 ms into item "a"

        val cut = jb.itemCut()
        assertEquals("a", cut.itemId)
        assertEquals(300, cut.playedMs)
        assertEquals(listOf("b"), cut.unheardItemIds)
    }

    /** At a boundary the earlier item counts as fully played; the cut starts the next at 0. */
    @Test
    fun atAnItemBoundaryTheEarlierItemCountsAsFullyPlayed() {
        val jb = JitterBuffer(24_000, prefillMs = 0)
        jb.markItemStart("a")
        jb.offer(silence(1_000, 24_000))
        jb.markItemStart("b")
        jb.offer(silence(600, 24_000))
        assertEquals(1_000, play(jb, 1_000)) // all of "a", none of "b"

        val cut = jb.itemCut()
        assertEquals("b", cut.itemId)
        assertEquals(0, cut.playedMs)
        assertTrue(cut.unheardItemIds.isEmpty())
    }

    @Test
    fun aBufferWithNothingOfferedCutsAtNothing() {
        val jb = JitterBuffer(24_000)
        val cut = jb.itemCut()
        assertNull(cut.itemId)
        assertEquals(0, cut.playedMs)
        assertTrue(cut.unheardItemIds.isEmpty())
    }

    /** A cut empties the buffer; the items before it must not leak into the next cut. */
    @Test
    fun clearForgetsTheItems() {
        val jb = JitterBuffer(24_000, prefillMs = 0)
        jb.markItemStart("a")
        jb.offer(silence(50, 24_000))
        jb.clear()
        assertNull(jb.itemCut().itemId)

        jb.markItemStart("b")
        jb.offer(silence(100, 24_000))
        val cut = jb.itemCut()
        assertEquals("b", cut.itemId)
        assertTrue(cut.unheardItemIds.isEmpty())
    }

    /** Reads exactly [ms] of audio; returns the ms actually read. */
    private fun play(jb: JitterBuffer, ms: Long): Long {
        val n = jb.read(ByteArray(Resampler.bytesForMs(ms, jb.sampleRate)), timeoutMs = 200)
        return Resampler.durationMs(n, jb.sampleRate)
    }
}

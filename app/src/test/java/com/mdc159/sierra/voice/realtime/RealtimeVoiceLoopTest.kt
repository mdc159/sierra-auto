package com.mdc159.sierra.voice.realtime

import com.mdc159.sierra.voice.FakeAudioFocus
import com.mdc159.sierra.voice.FakeMic
import com.mdc159.sierra.voice.FakePcmPlayer
import com.mdc159.sierra.voice.tts.AudioFocusGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RealtimeVoiceLoopTest {

    private class FakeChannel : RealtimeChannel {
        lateinit var listener: RealtimeListener
        val sent = Collections.synchronizedList(mutableListOf<ByteArray>())
        var cancels = 0
        var closes = 0
        val connected = CountDownLatch(1)
        override var isOpen = true

        override fun sendMicAudio(pcm: ByteArray, length: Int) {
            sent += pcm.copyOfRange(0, length)
        }

        override fun commitAndRespond() = Unit

        override fun cancelResponse() {
            cancels++
        }

        override fun close() {
            closes++
            isOpen = false
        }
    }

    private val mic = FakeMic()
    private val focus = FakeAudioFocus()
    private val channel = FakeChannel()
    private val players = Collections.synchronizedList(mutableListOf<FakePcmPlayer>())
    private val events = Collections.synchronizedList(mutableListOf<RealtimeVoiceLoop.Event>())
    private val pool = Executors.newSingleThreadExecutor()

    private fun loop(readyTimeoutMs: Long = 5_000) = RealtimeVoiceLoop(
        mic,
        { FakePcmPlayer().also { players += it } },
        focus,
        { l ->
            channel.listener = l
            channel.connected.countDown()
            channel
        },
        readyTimeoutMs = readyTimeoutMs,
        onEvent = { events += it },
    )

    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out")
            Thread.sleep(5)
        }
    }

    @Test
    fun fullDuplexWithBargeIn() {
        mic.feedMs(100, 500)
        val l = loop()
        val result = pool.submit<String> { l.run() }
        assertTrue(channel.connected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(AudioFocusGate.Mode.LISTEN), focus.requests)
        channel.listener.onReady()
        await { channel.sent.isNotEmpty() }
        assertEquals(100 * 32, channel.sent.sumOf { it.size }) // all mic audio forwarded

        channel.listener.onAudio(byteArrayOf(1, 2, 3, 4))
        await { players.isNotEmpty() && players[0].bytes().size == 4 }
        assertEquals(24_000, players[0].sampleRate)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), players[0].bytes())
        channel.listener.onUserTranscript("hello")
        channel.listener.onAssistantText("Hi.")
        channel.listener.onResponseDone()

        // Driver talks over the answer: queued audio is dropped and the response cancelled.
        channel.listener.onSpeechStarted()
        assertTrue(players[0].stopped)
        assertEquals(1, channel.cancels)
        channel.listener.onAudio(byteArrayOf(9))
        await { players.size == 2 && players[1].bytes().size == 1 }

        l.stop()
        mic.feed(ByteArray(0))
        assertEquals("Stopped.", result.get(5, TimeUnit.SECONDS))
        assertEquals(1, channel.closes)
        assertEquals(1, mic.stopped)
        assertFalse(focus.held)
        assertTrue(players.all { it.closed })
        assertTrue(events.contains(RealtimeVoiceLoop.Event.Ready))
        assertTrue(events.contains(RealtimeVoiceLoop.Event.UserSaid("hello")))
        assertTrue(events.contains(RealtimeVoiceLoop.Event.AssistantText("Hi.")))
        assertTrue(events.contains(RealtimeVoiceLoop.Event.ResponseDone))
        assertEquals(RealtimeVoiceLoop.Event.Ended("Stopped."), events.last())
    }

    @Test
    fun hostEndingTheMicEndsTheSession() {
        mic.feedMs(40, 500)
        mic.endSession()
        val l = loop()
        val result = pool.submit<String> { l.run() }
        assertTrue(channel.connected.await(5, TimeUnit.SECONDS))
        channel.listener.onReady()
        assertEquals("Microphone closed.", result.get(5, TimeUnit.SECONDS))
        assertEquals(1, channel.closes)

        // -1 before any audio: the host refused the microphone.
        val refused = FakeMic().apply { endSession() }
        val other = FakeChannel()
        val l2 = RealtimeVoiceLoop(refused, { FakePcmPlayer() }, focus, { l -> other.listener = l; other.connected.countDown(); other })
        val r2 = pool.submit<String> { l2.run() }
        assertTrue(other.connected.await(5, TimeUnit.SECONDS))
        other.listener.onReady()
        assertEquals("Car microphone unavailable. Try again.", r2.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun serverErrorEndsWithItsMessageAndNoReadyMeansTimeout() {
        val l = loop()
        val result = pool.submit<String> { l.run() }
        assertTrue(channel.connected.await(5, TimeUnit.SECONDS))
        channel.listener.onReady()
        channel.listener.onError("Invalid model")
        mic.feed(ByteArray(0))
        assertEquals("Invalid model", result.get(5, TimeUnit.SECONDS))

        val slow = FakeChannel()
        val l2 = RealtimeVoiceLoop(mic, { FakePcmPlayer() }, focus, { l -> slow.listener = l; slow }, readyTimeoutMs = 100)
        assertEquals("Realtime server did not answer.", l2.run())
        assertEquals(1, slow.closes)
        assertFalse(focus.held)
    }

    @Test
    fun deniedFocusNeverConnects() {
        focus.grant = false
        assertEquals("Couldn't get the microphone.", loop().run())
        assertEquals(1, channel.connected.count)
    }
}

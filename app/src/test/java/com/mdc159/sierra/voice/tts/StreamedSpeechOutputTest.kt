package com.mdc159.sierra.voice.tts

import com.mdc159.sierra.voice.FakeAudioFocus
import com.mdc159.sierra.voice.FakePcmPlayer
import com.mdc159.sierra.voice.Pcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamedSpeechOutputTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val focus = FakeAudioFocus()
    private var player = FakePcmPlayer()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun gateway(): GatewayTts =
        GatewayTts(server.url("/v1").toString(), "key", "openai/gpt-4o-mini-tts", "coral")

    private fun output(factory: TtsRequestFactory = gateway(), newPlayer: () -> PcmPlayer = { player }) =
        StreamedSpeechOutput(factory, focus, newPlayer, http)

    private fun pcm(n: Int, value: Int = 7): ByteArray = ByteArray(n) { value.toByte() }

    @Test
    fun oneRequestPerSentenceWithOpenAiShapedBody() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(100, 1))))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(100, 2))))

        val result = output().speak("Exit in two miles. The station is on the right.")

        assertEquals(SpeakResult.Completed, result)
        val first = server.takeRequest()
        assertEquals("/v1/audio/speech", first.path)
        assertEquals("Bearer key", first.getHeader("Authorization"))
        val body = JSONObject(first.body.readUtf8())
        assertEquals("openai/gpt-4o-mini-tts", body.getString("model"))
        assertEquals("coral", body.getString("voice"))
        assertEquals("pcm", body.getString("response_format"))
        assertEquals("Exit in two miles.", body.getString("input"))
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("The station is on the right.", second.getString("input"))

        assertEquals(24_000, player.sampleRate)
        assertArrayEquals(pcm(100, 1) + pcm(100, 2), player.bytes())
        assertTrue(player.finished)
        assertTrue(player.closed)
        assertEquals(listOf(AudioFocusGate.Mode.SPEAK), focus.requests)
        assertEquals(1, focus.abandons)
        assertFalse(focus.held)
    }

    @Test
    fun playbackStartsBeforeTheBodyHasFullyArrived() = runBlocking {
        val total = 64 * 1024
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(pcm(total)))
                .throttleBody(8 * 1024, 150, TimeUnit.MILLISECONDS),
        )
        val start = System.nanoTime()
        val job = async(Dispatchers.IO) { output().speak("A long sentence being streamed.") }
        assertTrue("first audio should arrive early", player.firstWrite.await(2, TimeUnit.SECONDS))
        val firstAudioMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(SpeakResult.Completed, job.await())
        val totalMs = (System.nanoTime() - start) / 1_000_000
        // Eight throttled slices take over a second; the first write must land well before the end.
        assertTrue("first audio at ${firstAudioMs}ms of ${totalMs}ms", firstAudioMs < totalMs / 2)
        assertEquals(total, player.bytes().size)
    }

    @Test
    fun wavReplyIsRecognisedAndHeaderStripped() = runBlocking {
        val samples = pcm(2_000, 3)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(Pcm.toWav(samples, 16_000))))
        assertEquals(SpeakResult.Completed, output().speak("Hello there."))
        assertEquals(16_000, player.sampleRate)
        assertArrayEquals(samples, player.bytes())
    }

    @Test
    fun tinyRawReplyStillPlays() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(10, 9))))
        assertEquals(SpeakResult.Completed, output().speak("Hi."))
        assertEquals(24_000, player.sampleRate)
        assertArrayEquals(pcm(10, 9), player.bytes())
    }

    @Test
    fun stopMidStreamInterruptsAndReleasesFocus() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(pcm(64 * 1024)))
                .throttleBody(4 * 1024, 200, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(10))))
        val out = output()
        val job = async(Dispatchers.IO) { out.speak("First sentence, long. Second sentence.") }
        assertTrue(player.firstWrite.await(2, TimeUnit.SECONDS))
        out.stop()
        assertEquals(SpeakResult.Interrupted, withTimeout(5_000) { job.await() })
        assertTrue(player.stopped)
        assertTrue(player.closed)
        assertEquals(1, focus.abandons)
        // The second sentence was never requested.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun focusLossStopsSpeech() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(pcm(64 * 1024)))
                .throttleBody(4 * 1024, 200, TimeUnit.MILLISECONDS),
        )
        val out = output()
        val job = async(Dispatchers.IO) { out.speak("Something long.") }
        assertTrue(player.firstWrite.await(2, TimeUnit.SECONDS))
        focus.loseFocus()
        assertEquals(SpeakResult.Interrupted, withTimeout(5_000) { job.await() })
    }

    @Test
    fun httpErrorBecomesReadableFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val result = output().speak("Hello.")
        assertEquals(SpeakResult.Failed("Voice error 401: bad key"), result)
        assertFalse(player.opened)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun mp3ReplyIsRejectedRatherThanPlayedAsNoise() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(pcm(100))))
        val result = output().speak("Hello.")
        assertTrue(result is SpeakResult.Failed)
        assertTrue((result as SpeakResult.Failed).message.contains("audio/mpeg"))
    }

    @Test
    fun deniedFocusFailsWithoutRequesting() = runBlocking {
        focus.grant = false
        val result = output().speak("Hello.")
        assertEquals(SpeakResult.Failed("Audio is busy. Try again."), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyTextCompletesImmediately() = runBlocking {
        assertEquals(SpeakResult.Completed, output().speak("   "))
        assertEquals(0, server.requestCount)
        assertEquals(0, focus.requests.size)
    }

    @Test
    fun elevenLabsRequestShape() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(50))))
        val factory = ElevenLabsTts("xi-key", "voice123", "eleven_flash_v2_5", server.url("/").toString())
        assertEquals(SpeakResult.Completed, output(factory).speak("Howdy."))
        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice123/stream?output_format=pcm_24000", req.path)
        assertEquals("xi-key", req.getHeader("xi-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Howdy.", body.getString("text"))
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))
    }

    @Test
    fun secondSpeakStopsTheFirst() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(pcm(64 * 1024)))
                .throttleBody(4 * 1024, 200, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm(10))))
        val firstPlayer = FakePcmPlayer()
        val secondPlayer = FakePcmPlayer()
        var n = 0
        val out = output(newPlayer = { if (n++ == 0) firstPlayer else secondPlayer })
        val first = async(Dispatchers.IO) { out.speak("Long first reply.") }
        assertTrue(firstPlayer.firstWrite.await(2, TimeUnit.SECONDS))
        delay(50)
        val second = async(Dispatchers.IO) { out.speak("Short.") }
        assertEquals(SpeakResult.Interrupted, withTimeout(5_000) { first.await() })
        assertEquals(SpeakResult.Completed, withTimeout(5_000) { second.await() })
        assertTrue(firstPlayer.stopped)
        assertFalse(secondPlayer.stopped)
    }

    @Test
    fun wavHeaderParsing() {
        val wav = Pcm.toWav(ByteArray(8), 22_050)
        val info = WavHeader.parse(wav)!!
        assertEquals(22_050, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(44, info.dataOffset)
        assertEquals(null, WavHeader.parse(ByteArray(44) { 1 }))
        assertEquals(null, WavHeader.parse(ByteArray(10)))
    }
}

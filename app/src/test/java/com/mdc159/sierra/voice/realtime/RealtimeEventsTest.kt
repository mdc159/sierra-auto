package com.mdc159.sierra.voice.realtime

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RealtimeEventsTest {

    private val config = RealtimeConfig(baseUrl = "http://gateway.example.ts.net:4000/v1", apiKey = "k", model = "openai/gpt-realtime-2.1", voice = "marin")

    @Test
    fun websocketUrlFromGatewayBase() {
        assertEquals("ws://gateway.example.ts.net:4000/v1/realtime?model=openai%2Fgpt-realtime-2.1", config.websocketUrl())
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime",
            config.copy(baseUrl = "https://api.openai.com", model = "gpt-realtime").websocketUrl(),
        )
        assertEquals("wss://h/v1/realtime?model=m", config.copy(baseUrl = "https://h/v1/", model = "m").websocketUrl())
    }

    @Test
    fun sessionUpdateUsesGaShapeByDefaultAndBetaShapeOnRequest() {
        val ga = JSONObject(RealtimeEvents.sessionUpdate(config))
        assertEquals("session.update", ga.getString("type"))
        val session = ga.getJSONObject("session")
        assertEquals("realtime", session.getString("type"))
        assertEquals("audio/pcm", session.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(24_000, session.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("marin", session.getJSONObject("audio").getJSONObject("output").getString("voice"))
        val vad = session.getJSONObject("audio").getJSONObject("input").getJSONObject("turn_detection")
        assertEquals("server_vad", vad.getString("type"))
        assertEquals(500, vad.getInt("silence_duration_ms"))
        assertTrue(vad.getBoolean("create_response"))
        assertTrue(session.getString("instructions").contains("Sierra"))
        assertEquals("openai/gpt-realtime-2.1-mini", RealtimeConfig.DEFAULT_MODEL)

        val beta = JSONObject(RealtimeEvents.sessionUpdate(config.copy(legacyEvents = true))).getJSONObject("session")
        assertEquals("pcm16", beta.getString("input_audio_format"))
        assertEquals("marin", beta.getString("voice"))
        assertEquals("server_vad", beta.getJSONObject("turn_detection").getString("type"))
    }

    @Test
    fun audioAppendIsBase64() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val o = JSONObject(RealtimeEvents.audioAppend(pcm, 2, 4))
        assertEquals("input_audio_buffer.append", o.getString("type"))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), Base64.getDecoder().decode(o.getString("audio")))
    }

    @Test
    fun parsesBothEventDialects() {
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(9, 8))
        val gaAudio = RealtimeEvents.parse("""{"type":"response.output_audio.delta","delta":"$b64"}""") as RealtimeEvents.Incoming.AudioDelta
        assertArrayEquals(byteArrayOf(9, 8), gaAudio.pcm)
        val betaAudio = RealtimeEvents.parse("""{"type":"response.audio.delta","delta":"$b64"}""") as RealtimeEvents.Incoming.AudioDelta
        assertArrayEquals(byteArrayOf(9, 8), betaAudio.pcm)
        assertEquals(RealtimeEvents.Incoming.AssistantTextDelta("Turn"), RealtimeEvents.parse("""{"type":"response.output_audio_transcript.delta","delta":"Turn"}"""))
        assertEquals(RealtimeEvents.Incoming.UserTranscript("find fuel"), RealtimeEvents.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":" find fuel "}"""))
        assertEquals(RealtimeEvents.Incoming.SpeechStarted, RealtimeEvents.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(RealtimeEvents.Incoming.ResponseDone, RealtimeEvents.parse("""{"type":"response.done","response":{"status":"completed"}}"""))
        assertEquals(RealtimeEvents.Incoming.Error("quota"), RealtimeEvents.parse("""{"type":"response.done","response":{"status":"failed","status_details":{"error":{"message":"quota"}}}}"""))
        assertEquals(RealtimeEvents.Incoming.Error("bad session"), RealtimeEvents.parse("""{"type":"error","error":{"message":"bad session"}}"""))
        assertEquals(RealtimeEvents.Incoming.SessionReady("session.updated"), RealtimeEvents.parse("""{"type":"session.updated"}"""))
        assertEquals(RealtimeEvents.Incoming.Unknown("rate_limits.updated"), RealtimeEvents.parse("""{"type":"rate_limits.updated"}"""))
        assertTrue(RealtimeEvents.parse("not json") is RealtimeEvents.Incoming.Error)
        assertTrue(RealtimeEvents.parse("""{"type":"response.audio.delta","delta":"%%%"}""") is RealtimeEvents.Incoming.Error)
    }

    @Test
    fun resamplerStretches16kTo24k() {
        // 4 samples: 0, 1000, 2000, 3000 → 6 samples by linear interpolation.
        val input = ByteArray(8)
        for ((i, v) in listOf(0, 1000, 2000, 3000).withIndex()) {
            input[2 * i] = (v and 0xFF).toByte()
            input[2 * i + 1] = (v shr 8).toByte()
        }
        val out = Resampler.resample(input, input.size, 16_000, 24_000)
        assertEquals(12, out.size)
        val samples = (0 until 6).map { ((out[2 * it + 1].toInt() shl 8) or (out[2 * it].toInt() and 0xFF)).toShort().toInt() }
        assertEquals(listOf(0, 666, 1333, 2000, 2666, 3000), samples)
        assertArrayEquals(input, Resampler.resample(input, input.size, 16_000, 16_000))
        assertEquals(0, Resampler.resample(ByteArray(1), 1, 16_000, 24_000).size)
    }
}

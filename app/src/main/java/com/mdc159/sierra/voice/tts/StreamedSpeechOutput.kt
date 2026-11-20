package com.mdc159.sierra.voice.tts

import com.mdc159.sierra.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Network text-to-speech with streamed playback: the reply is cut into sentences, each
 * sentence is fetched in turn, and audio goes to the [PcmPlayer] as soon as the first
 * bytes of the first sentence arrive. Fetching runs ahead of playback, so later sentences
 * are usually buffered before the earlier ones finish.
 */
class StreamedSpeechOutput(
    private val factory: TtsRequestFactory,
    private val focus: AudioFocusGate,
    private val playerFactory: () -> PcmPlayer,
    private val http: OkHttpClient = Http.client,
    private val chunker: (String) -> List<String> = { SentenceChunker.chunks(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpeechOutput {

    override val name: String get() = factory.name

    private class Active(val player: PcmPlayer) {
        val stopped = AtomicBoolean(false)
        val call = AtomicReference<Call?>()
        @Volatile var producer: Job? = null
    }

    private val active = AtomicReference<Active?>()

    private sealed class Piece {
        class Format(val sampleRate: Int) : Piece()
        class Audio(val bytes: ByteArray) : Piece()
    }

    override suspend fun speak(text: String): SpeakResult {
        val chunks = chunker(text)
        if (chunks.isEmpty()) return SpeakResult.Completed
        stop() // one utterance at a time
        val player = playerFactory()
        val me = Active(player)
        active.set(me)
        try {
            if (!focus.request(AudioFocusGate.Mode.SPEAK) { stopActive(me) }) {
                return SpeakResult.Failed("Audio is busy. Try again.")
            }
            return withContext(ioDispatcher) { stream(me, chunks) }
        } finally {
            // Finished, interrupted or failed: make sure nothing is still running, then release.
            me.stopped.set(true)
            me.call.get()?.cancel()
            me.producer?.cancel()
            player.close()
            focus.abandon()
            active.compareAndSet(me, null)
        }
    }

    private suspend fun stream(me: Active, chunks: List<String>): SpeakResult = coroutineScope {
        val pieces = Channel<Piece>(Channel.UNLIMITED)
        me.producer = launch {
            try {
                var first = true
                for (chunk in chunks) {
                    if (me.stopped.get()) break
                    fetch(me, chunk, first, pieces)
                    first = false
                }
                pieces.close()
            } catch (e: CancellationException) {
                pieces.close()
                throw e
            } catch (e: Exception) {
                // A cancelled call surfaces as "Socket closed"; that is a stop, not a failure.
                if (me.stopped.get()) pieces.close() else pieces.close(e)
            }
        }
        var opened = false
        try {
            for (piece in pieces) {
                if (me.stopped.get()) break
                when (piece) {
                    is Piece.Format -> if (!opened) {
                        me.player.open(piece.sampleRate)
                        opened = true
                    }
                    is Piece.Audio -> if (opened && !me.player.write(piece.bytes, 0, piece.bytes.size)) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtsException) {
            me.producer?.cancel()
            if (me.stopped.get()) return@coroutineScope SpeakResult.Interrupted
            // If audio already played, the rest is lost; report it rather than pretend.
            return@coroutineScope SpeakResult.Failed(e.message ?: "Voice failed.")
        } catch (e: IOException) {
            me.producer?.cancel()
            if (me.stopped.get()) return@coroutineScope SpeakResult.Interrupted
            return@coroutineScope SpeakResult.Failed(Http.describe(e))
        } catch (e: Exception) {
            me.producer?.cancel()
            if (me.stopped.get()) return@coroutineScope SpeakResult.Interrupted
            return@coroutineScope SpeakResult.Failed(e.message?.take(80) ?: "Voice failed.")
        }
        if (me.stopped.get()) {
            me.producer?.cancel()
            return@coroutineScope SpeakResult.Interrupted
        }
        if (opened) me.player.finish()
        if (me.stopped.get()) SpeakResult.Interrupted else SpeakResult.Completed
    }

    /** Executes one chunk's request and feeds its body to [pieces]. */
    private fun fetch(me: Active, text: String, first: Boolean, pieces: Channel<Piece>) {
        val call = http.newCall(factory.build(text))
        me.call.set(call)
        if (me.stopped.get()) {
            call.cancel()
            return
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (me.stopped.get()) return
            throw e
        }
        response.use { r ->
            val body = r.body ?: throw TtsException("${factory.name}: empty reply.")
            if (!r.isSuccessful) {
                val detail = runCatching { body.string() }.getOrDefault("")
                throw TtsException("Voice error ${r.code}: ${errorDetail(detail)}".trimEnd(' ', ':'))
            }
            val type = r.header("Content-Type").orEmpty().lowercase()
            if (type.startsWith("audio/mpeg") || type.startsWith("audio/mp3") || type.contains("json")) {
                throw TtsException("Voice server sent ${type.substringBefore(';')}, not PCM.")
            }
            pump(me, body.byteStream(), first, pieces)
        }
    }

    private fun pump(me: Active, input: InputStream, first: Boolean, pieces: Channel<Piece>) {
        val buf = ByteArray(8 * 1024)
        var header: ByteArray? = if (first) ByteArray(0) else null
        var formatSent = !first
        while (!me.stopped.get()) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (!formatSent) {
                // Collect the first bytes so a WAV header can be recognised and skipped.
                header = header!! + buf.copyOfRange(0, n)
                if (header.size < WavHeader.MIN_BYTES) continue
                val wav = WavHeader.parse(header)
                val rate = wav?.sampleRate ?: factory.sampleRate
                pieces.trySend(Piece.Format(rate))
                formatSent = true
                val skip = wav?.dataOffset ?: 0
                val payload = header.copyOfRange(skip, header.size)
                if (payload.isNotEmpty()) pieces.trySend(Piece.Audio(payload))
                continue
            }
            pieces.trySend(Piece.Audio(buf.copyOfRange(0, n)))
        }
        if (!formatSent && header != null) {
            // Very short reply (under the header window): treat it as raw PCM.
            pieces.trySend(Piece.Format(factory.sampleRate))
            if (header.isNotEmpty()) pieces.trySend(Piece.Audio(header))
        }
    }

    override fun stop() {
        active.get()?.let { stopActive(it) }
    }

    private fun stopActive(me: Active) {
        if (!me.stopped.compareAndSet(false, true)) return
        me.player.stop()
        me.call.get()?.cancel()
        me.producer?.cancel()
    }

    override fun close() = stop()

    companion object {
        fun errorDetail(body: String): String =
            com.mdc159.sierra.agent.AgentClient.errorDetail(body)
    }
}

/** A text-to-speech failure with a message short enough for the car screen. */
class TtsException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Minimal RIFF/WAVE header reader: sample rate and where the PCM data starts. */
object WavHeader {
    /** Enough bytes to hold a canonical 44-byte header and recognise it. */
    const val MIN_BYTES = 44

    class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Int)

    /** Returns null when [bytes] does not start with a RIFF/WAVE header. */
    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < MIN_BYTES) return null
        if (!(ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE")) return null
        var pos = 12
        var sampleRate = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos)
            val size = le32(bytes, pos + 4)
            if (id == "fmt " && pos + 24 <= bytes.size) {
                channels = le16(bytes, pos + 10)
                sampleRate = le32(bytes, pos + 12)
                bits = le16(bytes, pos + 22)
            }
            if (id == "data") {
                return Info(if (sampleRate > 0) sampleRate else 24_000, channels, bits, pos + 8)
            }
            // Streaming encoders write size 0 or -1 for an unknown length; "data" is normally next.
            pos += 8 + (if (size < 0 || id == "RIFF") 0 else size)
            if (size % 2 == 1) pos++
        }
        return null
    }

    private fun ascii(b: ByteArray, at: Int): String =
        String(b, at, 4, Charsets.US_ASCII)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}

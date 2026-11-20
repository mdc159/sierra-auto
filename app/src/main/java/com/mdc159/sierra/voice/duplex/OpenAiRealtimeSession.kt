package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.agent.AgentConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenAI Realtime protocol (the GA websocket API) against OpenAI or any compatible
 * gateway. Server-side turn detection, function calling, and client-side truncation
 * of the assistant's audio when the driver talks over it.
 *
 * Event names and fields follow the OpenAI Node SDK's realtime types
 * (`openai/openai-node`, `src/resources/realtime/realtime.ts`), which is the vendor's
 * own definition of the wire protocol. The older beta names (`response.audio.delta`,
 * `response.audio_transcript.delta`) are also accepted so a gateway pinned to the beta
 * protocol still works. Audio is 16-bit PCM, mono, 24 kHz ("only a 24kHz sample rate is
 * supported" for `audio/pcm`), base64 in JSON text frames.
 */
class OpenAiRealtimeSession(
    private val config: DuplexConfig,
    private val tools: ToolRegistry,
    client: OkHttpClient,
    timing: WebSocketSessionBase.Timing = WebSocketSessionBase.Timing(),
    /**
     * The settings as they are now, asked on every connect. The base URL and key are taken
     * from it, so a key refreshed after a 401 reaches the next connection of this session
     * instead of the one captured when the session was built. Null keeps [config]'s.
     */
    private val credentials: (() -> DuplexConfig)? = null,
) : WebSocketSessionBase(client, timing) {

    override val provider = DuplexProvider.OPENAI_REALTIME
    override val inputSampleRate = SAMPLE_RATE
    override val outputSampleRate = SAMPLE_RATE

    private val sessionCreated = AtomicBoolean(false)

    // Per-response state, touched only from the socket thread (plus interrupt()).
    @Volatile
    private var activeResponseId: String? = null

    @Volatile
    private var cancelledByServer = false

    @Volatile
    private var audioItemId: String? = null

    @Volatile
    private var audioContentIndex = 0

    @Volatile
    private var audioReceivedBytes = 0L

    @Volatile
    private var truncatedItemId: String? = null

    /**
     * Response and item ids whose remaining audio must not be played: cancelled by the
     * server when the driver spoke, cancelled by a tap, or truncated. Deltas already on
     * the wire when playback was cut would otherwise start it again.
     */
    private val cancelledIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private var droppedDeltas = 0

    /**
     * Responses whose `response.done` was handled. After an upstream stall the server has sent a
     * second `response.done` (status cancelled, turn_detected) for a response already reported
     * completed 20 s earlier; handled again it would cut whatever reply is playing now.
     */
    private val doneIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** The active response produced audio or a tool call (a cancelled empty one answered nothing). */
    @Volatile
    private var activeHadOutput = false

    /** When the last input transcript completed: the gateway's own response.create follows it. */
    @Volatile
    private var lastTranscriptAt = 0L

    /** Audio sent this connection, to compare with the server's `audio_start_ms` / `audio_end_ms`. */
    private val sentAudioBytes = AtomicLong()

    // Round-trip probes: a harmless request every PROBE_INTERVAL_MS whose error reply echoes our
    // event_id. The server answers it as soon as it reads it, so its round trip measures the
    // whole path phone -> gateway -> service -> gateway -> phone, and a quiet socket is never
    // mistaken for a stalled one.
    private val probeSeq = AtomicInteger()
    private val probesOutstanding = ConcurrentHashMap<String, Long>()
    private var probeTimer: ScheduledExecutorService? = null

    @Volatile
    private var probeStallLogged = false
    private var probeCount = 0
    private var probeMaxRttMs = 0L
    private var probeRttSumMs = 0L

    // When a `response.create` may be sent. Guarded by responseLock: tools finish on the tool
    // thread and typed turns arrive on the caller's, while the socket thread tracks responses.
    private val responseLock = Any()
    private var responsePending = false
    private var responseRequestedAt = 0L

    /** event_id of the app's own `response.create` that has not had its response.created yet. */
    private var ownRequestId: String? = null

    /** The service echoed our `response.metadata` at least once; until then any response counts as ours. */
    private var metadataEchoSeen = false
    private var userSpeaking = false
    private var vadResponseDueBy = 0L
    private var responseCreates = 0

    /**
     * Something a new response may answer arrived since the last `response.created`: a
     * committed driver turn, or a `response.create` this app sent (typed turn, tool result).
     */
    private var inputSinceResponse = true

    /** The response the app cancelled as an unrequested duplicate (see "response.created"). */
    @Volatile
    private var duplicateResponseId: String? = null

    private val userTranscripts = HashMap<String, StringBuilder>()
    private val assistantTranscript = StringBuilder()

    override fun buildRequest(): Request {
        // Read at connect time, not at construction: the vault may have a newer key by now.
        val live = credentials?.let { runCatching(it).getOrNull() }
            ?.takeIf { it.provider == DuplexProvider.OPENAI_REALTIME && it.openAiBaseUrl.isNotBlank() }
        val baseUrl = live?.openAiBaseUrl ?: config.openAiBaseUrl
        val apiKey = live?.openAiApiKey ?: config.openAiApiKey
        if (live != null && apiKey != config.openAiApiKey) DuplexLog.i("openai connect: using credentials newer than the session's")
        val url = try {
            AgentConfig.endpoint(baseUrl, "realtime")
                .toHttpUrlOrThrow()
                .newBuilder()
                .setQueryParameter("model", config.openAiModel)
                .build()
        } catch (e: IllegalArgumentException) {
            throw DuplexException("The realtime base URL is not valid.", e)
        }
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    override fun onSocketOpen(socket: WebSocket) {
        startProbes()
        DuplexLog.i("openai tx session.update model=${config.openAiModel} voice=${config.openAiVoice} transcription=${config.openAiTranscriptionModel.ifBlank { "off" }} tools=${tools.all.joinToString(",") { it.name }}")
        socket.send(buildSessionUpdate().toString())
    }

    /** The `session.update` event that configures audio, turn detection and tools. */
    fun buildSessionUpdate(): JSONObject {
        val pcm = JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE)
        val input = JSONObject()
            .put("format", pcm)
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.6)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 500)
                    .put("create_response", true)
                    .put("interrupt_response", true),
            )
        // The phone sits a metre or more from the speaker: far-field noise reduction before the VAD.
        input.put("noise_reduction", JSONObject().put("type", "far_field"))
        if (config.openAiTranscriptionModel.isNotBlank()) {
            input.put("transcription", JSONObject().put("model", config.openAiTranscriptionModel))
        }
        val output = JSONObject().put("format", pcm)
        if (config.openAiVoice.isNotBlank()) output.put("voice", config.openAiVoice)
        val session = JSONObject()
            .put("type", "realtime")
            .put("output_modalities", JSONArray().put("audio"))
            .put("instructions", config.instructions)
            .put("audio", JSONObject().put("input", input).put("output", output))
        if (tools.all.isNotEmpty()) {
            val arr = JSONArray()
            tools.all.forEach {
                arr.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", it.name)
                        .put("description", it.description)
                        .put("parameters", it.parameters),
                )
            }
            session.put("tools", arr).put("tool_choice", "auto")
        }
        return JSONObject().put("type", "session.update").put("session", session)
    }

    override fun onHandshakeTimeout(): Boolean {
        if (!sessionCreated.get()) return false
        emit(DuplexEvent.Error("The gateway did not confirm the session settings; continuing.", fatal = false))
        return true
    }

    override fun onBeforeReconnect() {
        sessionCreated.set(false)
        activeResponseId = null
        cancelledByServer = false
        audioItemId = null
        audioReceivedBytes = 0
        truncatedItemId = null
        assistantTranscript.setLength(0)
        cancelledIds.clear()
        doneIds.clear()
        sentAudioBytes.set(0)
        probesOutstanding.clear()
        probeStallLogged = false
        synchronized(responseLock) {
            responsePending = false
            responseRequestedAt = 0
            ownRequestId = null
            userSpeaking = false
            vadResponseDueBy = 0
        }
    }

    /** Stop must not wait for the owner's agent: abort a tool call that is still running. */
    override fun onClose() {
        probeTimer?.shutdownNow()
        tools.cancel()
    }

    private fun startProbes() {
        if (probeTimer != null) return
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "duplex-probe").apply { isDaemon = true } }
        probeTimer = timer
        timer.scheduleWithFixedDelay({ runCatching { probeTick() } }, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun probeTick() {
        if (isClosed || isHandshaking) return
        // A deferred request whose expected reply never came (a turn the server did not answer).
        val retry = synchronized(responseLock) {
            responsePending && activeResponseId == null && !userSpeaking && System.currentTimeMillis() >= vadResponseDueBy
        }
        if (retry) requestResponse("deferred, no reply came")
        val now = System.nanoTime() / 1_000_000
        probesOutstanding.entries.removeIf { (id, at) ->
            (now - at >= PROBE_GIVE_UP_MS).also { if (it) DuplexLog.w("probe lost id=$id after ${now - at}ms") }
        }
        val oldest = probesOutstanding.values.minOrNull()
        if (oldest != null && now - oldest >= PROBE_STALL_MS && !probeStallLogged) {
            probeStallLogged = true
            DuplexLog.w("probe stall: no reply for ${now - oldest}ms; ${stallContext()}")
        }
        // A few outstanding are enough to time the stall; do not pile requests onto it.
        if (probesOutstanding.size >= MAX_PROBES_OUTSTANDING) return
        val id = "${PROBE_PREFIX}${probeSeq.incrementAndGet()}"
        probesOutstanding[id] = now
        send(JSONObject().put("type", "conversation.item.retrieve").put("event_id", id).put("item_id", id))
    }

    private fun onProbeReply(id: String) {
        val sentAt = probesOutstanding.remove(id) ?: return
        val rtt = System.nanoTime() / 1_000_000 - sentAt
        probeCount++
        probeRttSumMs += rtt
        if (rtt > probeMaxRttMs) probeMaxRttMs = rtt
        if (probeStallLogged && probesOutstanding.isEmpty()) {
            probeStallLogged = false
            DuplexLog.w("probe stall over: rttMs=$rtt")
        } else if (rtt >= PROBE_SLOW_MS) {
            DuplexLog.i("probe slow rttMs=$rtt")
        }
        if (probeCount % PROBE_SUMMARY_EVERY == 0) {
            DuplexLog.i("probe summary n=$probeCount meanRttMs=${probeRttSumMs / probeCount} maxRttMs=$probeMaxRttMs")
        }
    }

    private val sentAudioMs: Long get() = Resampler.durationMs(sentAudioBytes.get().toInt(), SAMPLE_RATE)

    override fun isQuietFrame(message: JSONObject): Boolean =
        message.optString("type") == "error" &&
            message.optJSONObject("error")?.optString("event_id")?.startsWith(PROBE_PREFIX) == true

    override fun stallContext(): String {
        val now = System.nanoTime() / 1_000_000
        val oldest = probesOutstanding.values.minOrNull()
        return "probesOutstanding=${probesOutstanding.size} oldestProbeMs=${oldest?.let { now - it } ?: 0} sentAudioMs=$sentAudioMs " +
            "activeResponse=${activeResponseId ?: "none"} toolRunning=${toolsRunning.get()}"
    }

    private val toolsRunning = AtomicInteger()

    /**
     * Where in the audio the server placed a VAD event against how much the phone has sent.
     * lagMs is normally the VAD's own delay (under 1 s); seconds more mean the server read our
     * audio late (uplink, gateway or service backlog), not that the event was slow to come down.
     */
    private fun audioClock(message: JSONObject, field: String): String {
        val sent = sentAudioMs
        if (!message.has(field)) return "sentAudioMs=$sent"
        val at = message.optLong(field)
        return "$field=$at sentAudioMs=$sent lagMs=${sent - at}"
    }

    private fun markCancelled(id: String) {
        synchronized(cancelledIds) {
            cancelledIds.add(id)
            while (cancelledIds.size > MAX_CANCELLED_IDS) cancelledIds.remove(cancelledIds.first())
        }
    }

    /**
     * Asks for a reply, unless one is already being made: the server refuses a second
     * `response.create` while a response is active. A tool can take many seconds, and the
     * driver may have spoken (or typed) in the meantime.
     *  - a response is active, or one was just requested: ask again when it is done;
     *  - the driver is mid-turn: ask again once the reply to that turn is done. That reply
     *    already sees the new item, but the model may answer only the driver's words (observed
     *    2026-11-20: a tool answer was never spoken), so the result gets its own response;
     *  - otherwise: send it now.
     */
    private fun requestResponse(why: String) {
        synchronized(responseLock) {
            val now = System.currentTimeMillis()
            // Ours is tagged (metadata), so it is known when it arrives; the wait is only a safety net.
            val requested = ownRequestId != null && now - responseRequestedAt < RESPONSE_CREATE_WAIT_MS
            when {
                activeResponseId != null || requested -> {
                    responsePending = true
                    DuplexLog.i("openai response.create deferred ($why): response ${activeResponseId ?: "requested"} in progress")
                }
                userSpeaking || now < vadResponseDueBy -> {
                    responsePending = true
                    DuplexLog.i("openai response.create deferred ($why): the driver is mid-turn; asking after that reply")
                }
                else -> {
                    responsePending = false
                    responseRequestedAt = now
                    inputSinceResponse = true
                    val eventId = "${EVENT_PREFIX}rc_${++responseCreates}"
                    ownRequestId = eventId
                    DuplexLog.i("openai tx response.create ($why) event=$eventId")
                    // The metadata comes back on response.created: the app's own responses are
                    // recognised exactly and never taken for the gateway's duplicate.
                    send(
                        JSONObject()
                            .put("type", "response.create")
                            .put("event_id", eventId)
                            .put("response", JSONObject().put("metadata", JSONObject().put(METADATA_KEY, eventId))),
                    )
                }
            }
        }
    }

    override fun onJson(message: JSONObject) {
        when (val type = message.optString("type")) {
            "session.created" -> sessionCreated.set(true)
            "session.updated" -> handshakeComplete()
            "error" -> {
                val err = message.optJSONObject("error")
                val text = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Voice service error."
                // event_id names the client event that caused the error; ours are "sierra_…".
                val cause = err?.takeUnless { it.isNull("event_id") }?.optString("event_id")?.ifBlank { null }
                val code = err?.optString("code").orEmpty()
                if (cause != null && cause.startsWith(PROBE_PREFIX)) {
                    onProbeReply(cause)
                    return
                }
                if (code == "conversation_already_has_active_response" && cause?.startsWith(EVENT_PREFIX) == true) {
                    // Our request crossed a response the server started meanwhile: ask again when it is done.
                    synchronized(responseLock) {
                        if (cause == ownRequestId) { ownRequestId = null; responseRequestedAt = 0 }
                        responsePending = true
                    }
                    DuplexLog.i("openai response.create $cause refused (response in progress); deferred")
                    return
                }
                if (code == "response_cancel_not_active") {
                    // A cancel that lost the race with the response's own end: nothing to cancel, nothing wrong.
                    DuplexLog.i("openai cancel arrived after the response ended (causeEvent=${DuplexLog.redact(cause ?: "none", 60)})")
                    return
                }
                if (code == "conversation_already_has_active_response" && cause?.startsWith(EVENT_PREFIX) != true) {
                    // Not our request: observed 2026-11-20 about 70 ms after every
                    // input_audio_transcription.completed of a spoken turn, while the server's own
                    // VAD response was already running. The gateway (LiteLLM's realtime transcript
                    // gate) sends its own response.create then; the server refuses it, and the
                    // reply in progress is unaffected. Nothing for the owner to see.
                    DuplexLog.i("openai ignored: a response.create the app did not send was refused (response in progress)")
                    return
                }
                DuplexLog.w("openai error type=${err?.optString("type")} code=$code causeEvent=${DuplexLog.redact(cause ?: "none", 60)} handshaking=$isHandshaking message=${DuplexLog.redact(text)}")
                // A refused response.create never gets its response.created.
                synchronized(responseLock) {
                    if (cause != null && cause == ownRequestId) { ownRequestId = null; responseRequestedAt = 0 }
                }
                if (isHandshaking) handshakeFailed(text) else emit(DuplexEvent.Error(text, fatal = false))
            }
            "input_audio_buffer.speech_started" -> {
                // With interrupt_response=true the server cancels the response itself.
                DuplexLog.i("openai barge-in: server heard speech; activeResponse=${activeResponseId ?: "none"} ${audioClock(message, "audio_start_ms")}")
                synchronized(responseLock) { userSpeaking = true }
                activeResponseId?.let {
                    cancelledByServer = true
                    markCancelled(it)
                }
                emit(DuplexEvent.UserStartedSpeaking)
            }
            "input_audio_buffer.committed" -> synchronized(responseLock) { inputSinceResponse = true }
            "input_audio_buffer.speech_stopped" -> {
                DuplexLog.i("openai speech stopped ${audioClock(message, "audio_end_ms")}")
                synchronized(responseLock) {
                    userSpeaking = false
                    vadResponseDueBy = System.currentTimeMillis() + VAD_RESPONSE_WAIT_MS
                }
                emit(DuplexEvent.UserStoppedSpeaking)
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val id = message.optString("item_id")
                val sb = userTranscripts.getOrPut(id) { StringBuilder() }
                sb.append(message.optString("delta"))
                emit(DuplexEvent.UserTranscript(sb.toString(), final = false))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                lastTranscriptAt = System.currentTimeMillis()
                userTranscripts.remove(message.optString("item_id"))
                emit(DuplexEvent.UserTranscript(message.optString("transcript").trim(), final = true))
            }
            "response.created" -> {
                val response = message.optJSONObject("response")
                val id = response?.optString("id")?.ifBlank { null } ?: "?"
                val tag = response?.optJSONObject("metadata")?.optString(METADATA_KEY)?.ifBlank { null }
                val now = System.currentTimeMillis()
                val (unrequested, why) = synchronized(responseLock) {
                    activeResponseId = id
                    val ours = tag != null
                    if (ours) metadataEchoSeen = true
                    // A gateway that drops the metadata: fall back to "the next response is ours".
                    if ((ours && tag == ownRequestId) || !metadataEchoSeen) { ownRequestId = null; responseRequestedAt = 0 }
                    val fresh = ours || inputSinceResponse
                    inputSinceResponse = false
                    vadResponseDueBy = 0
                    // The gateway's response.create follows an input transcript within ~250 ms
                    // (more after a stall, but in the same burst). Nothing else is cancelled.
                    val afterTranscript = now - lastTranscriptAt < DUPLICATE_WINDOW_MS
                    (!fresh && afterTranscript) to when {
                        ours -> "app request $tag"
                        fresh -> "new input"
                        else -> "no new input, ${now - lastTranscriptAt}ms after a transcript"
                    }
                }
                droppedDeltas = 0
                cancelledByServer = false
                activeHadOutput = false
                audioItemId = null
                audioReceivedBytes = 0
                assistantTranscript.setLength(0)
                DuplexLog.i("openai response created id=$id ($why)")
                if (unrequested) {
                    // Nothing new to answer: the gateway (LiteLLM v1.102 sends response.create after
                    // every input_audio_transcription.completed) asked for a second answer to a turn
                    // already answered. Observed 2026-11-20; the server answers the same turn twice.
                    DuplexLog.i("openai cancelling unrequested duplicate response id=$id")
                    duplicateResponseId = id
                    cancelledByServer = true
                    markCancelled(id)
                    send(JSONObject().put("type", "response.cancel").apply { if (id != "?") put("response_id", id) })
                }
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                val itemId = message.optString("item_id").ifBlank { null }
                val responseId = message.optString("response_id").ifBlank { null }
                // Audio of a reply that was cancelled or truncated: playback was already cut. Keyed on
                // the delta's own ids; the session-wide flag only for a gateway that omits them.
                val cancelled = (itemId != null && itemId in cancelledIds) ||
                    if (responseId != null) responseId in cancelledIds else cancelledByServer
                if (responseId == null || responseId == activeResponseId) activeHadOutput = true
                if (cancelled) {
                    if (droppedDeltas++ == 0) DuplexLog.i("openai dropping audio of interrupted reply response=${responseId ?: activeResponseId} item=$itemId")
                    return
                }
                val pcm = Base64.getDecoder().decode(message.optString("delta"))
                if (itemId != audioItemId) {
                    audioItemId = itemId
                    audioReceivedBytes = 0
                    audioContentIndex = message.optInt("content_index", 0)
                }
                audioReceivedBytes += pcm.size
                emit(DuplexEvent.AssistantAudio(pcm, SAMPLE_RATE, itemId))
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                assistantTranscript.append(message.optString("delta"))
                emit(DuplexEvent.AssistantTranscript(assistantTranscript.toString(), final = false))
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                emit(DuplexEvent.AssistantTranscript(message.optString("transcript").trim(), final = true))
            }
            "response.function_call_arguments.done" -> {
                val callId = message.optString("call_id")
                val name = message.optString("name")
                val args = message.optString("arguments")
                activeHadOutput = true
                DuplexLog.i("openai tool call queued name=$name call=$callId")
                emit(DuplexEvent.ToolCall(callId, name, args))
                toolExecutor.execute {
                    toolsRunning.incrementAndGet()
                    try { runTool(callId, name, args) } finally { toolsRunning.decrementAndGet() }
                }
            }
            "response.done" -> {
                val response = message.optJSONObject("response")
                val status = response?.optString("status").orEmpty()
                val reason = response?.optJSONObject("status_details")?.let { d ->
                    d.optString("reason").ifBlank { d.optJSONObject("error")?.optString("code").orEmpty() }
                }.orEmpty()
                val doneId = response?.optString("id")?.ifBlank { null }
                val active = activeResponseId
                if (doneId != null && (doneId in doneIds || (active != null && active != "?" && doneId != active))) {
                    // A late or repeated done of an earlier response: it must not end the current one.
                    DuplexLog.i("openai stale response.done ignored id=$doneId status=$status${if (reason.isBlank()) "" else " reason=$reason"} active=${active ?: "none"}")
                    return
                }
                doneId?.let {
                    synchronized(doneIds) {
                        doneIds.add(it)
                        while (doneIds.size > MAX_CANCELLED_IDS) doneIds.remove(doneIds.first())
                    }
                }
                DuplexLog.i("openai response done id=$doneId status=$status${if (reason.isBlank()) "" else " reason=$reason"} audioMs=${Resampler.durationMs(audioReceivedBytes.toInt(), SAMPLE_RATE)} droppedDeltas=$droppedDeltas")
                val isDuplicate = doneId != null && doneId == duplicateResponseId
                val pending = synchronized(responseLock) {
                    activeResponseId = null
                    // Cancelled before it said or did anything: the input it was for is still unanswered.
                    if (status == "cancelled" && !activeHadOutput && !isDuplicate) inputSinceResponse = true
                    responsePending
                }
                if (isDuplicate) {
                    // Its audio was never played; a cancelled AssistantDone would cut the reply
                    // still playing from the previous response.
                    duplicateResponseId = null
                } else {
                    emit(DuplexEvent.AssistantDone(cancelled = status == "cancelled"))
                }
                if (pending) requestResponse("deferred")
            }
            else -> Unit // session.updated-adjacent chatter, rate limits, item lifecycle: not needed.
        }
    }

    private fun runTool(callId: String, name: String, args: String) {
        val outcome = tools.run(name, args)
        emit(DuplexEvent.ToolResult(callId, name, outcome.result, outcome.isError))
        if (isClosed) {
            DuplexLog.i("openai tool result dropped: session closed (call=$callId)")
            return
        }
        DuplexLog.i("openai tx function_call_output call=$callId error=${outcome.isError}")
        val output = if (outcome.isError) "Error: ${outcome.result}" else outcome.result
        send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", output),
                ),
        )
        requestResponse("tool result")
    }

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || isClosed) return
        sentAudioBytes.addAndGet(length.toLong())
        val b64 = Base64.getEncoder().encodeToString(
            if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length),
        )
        send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64))
    }

    /**
     * Stops the current reply. If the server already cancelled it (speech detected), only
     * the truncate is sent, so the transcript the model keeps matches what the driver heard.
     * `audio_end_ms` is clamped to the audio actually received, which the server rejects
     * otherwise.
     */
    override fun interrupt(playedMs: Long) {
        if (isClosed) return
        val active = activeResponseId
        if (active != null && !cancelledByServer) {
            DuplexLog.i("openai interrupt: tx response.cancel id=$active")
            // Id-exact: a cancel that reaches the server late must not stop a newer response.
            send(JSONObject().put("type", "response.cancel").apply { if (active != "?") put("response_id", active) })
            cancelledByServer = true
            markCancelled(active)
        }
        val itemId = audioItemId
        if (itemId == null) {
            DuplexLog.i("openai interrupt: no reply audio yet, nothing to truncate")
            return
        }
        if (itemId == truncatedItemId) return
        truncatedItemId = itemId
        markCancelled(itemId)
        val receivedMs = Resampler.durationMs(audioReceivedBytes.toInt(), SAMPLE_RATE)
        if (activeResponseId == null && playedMs >= receivedMs - TRUNCATE_SLACK_MS) {
            // The reply had finished playing: nothing the driver missed, nothing to truncate.
            DuplexLog.i("openai interrupt: reply already played to the end (playedMs=$playedMs receivedMs=$receivedMs), no truncate")
            return
        }
        DuplexLog.i("openai interrupt: tx conversation.item.truncate item=$itemId playedMs=$playedMs receivedMs=$receivedMs")
        send(
            JSONObject()
                .put("type", "conversation.item.truncate")
                .put("item_id", itemId)
                .put("content_index", audioContentIndex)
                .put("audio_end_ms", playedMs.coerceIn(0, receivedMs)),
        )
    }

    override fun sendText(text: String) {
        if (text.isBlank() || isClosed) {
            DuplexLog.w("openai typed turn ignored (blank=${text.isBlank()} closed=$isClosed)")
            return
        }
        DuplexLog.i("openai tx typed user turn chars=${text.length}")
        send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "input_text").put("text", text)),
                        ),
                ),
        )
        requestResponse("typed turn")
    }

    companion object {
        const val SAMPLE_RATE = 24_000

        /** Prefix of the event_id on requests this app sends, to tell its own errors from others'. */
        private const val EVENT_PREFIX = "sierra_"

        /** Round-trip probes: their own prefix (inside [EVENT_PREFIX]), cadence and thresholds. */
        private const val PROBE_PREFIX = "sierra_probe_"
        private const val PROBE_INTERVAL_MS = 2_000L
        private const val PROBE_STALL_MS = 3_000L
        private const val PROBE_SLOW_MS = 1_500L
        private const val PROBE_GIVE_UP_MS = 60_000L
        private const val MAX_PROBES_OUTSTANDING = 3
        private const val PROBE_SUMMARY_EVERY = 30

        /** `response.metadata` key carrying the event_id of the app's own response.create. */
        private const val METADATA_KEY = "sierra_request"

        /** How soon after an input transcript the gateway's own response.create shows up. */
        private const val DUPLICATE_WINDOW_MS = 2_500L

        /** Played within this of everything received counts as played to the end. */
        private const val TRUNCATE_SLACK_MS = 100L

        private const val MAX_CANCELLED_IDS = 32

        /**
         * How long a sent `response.create` counts as in progress before its `response.created`
         * (or refusal). Long, because an upstream stall has held one for 3 s and more.
         */
        private const val RESPONSE_CREATE_WAIT_MS = 15_000L

        /** How long after the driver stops speaking the server's own response is still expected. */
        private const val VAD_RESPONSE_WAIT_MS = 3_000L

        private fun String.toHttpUrlOrThrow(): HttpUrl {
            // OkHttp speaks websockets over http(s) URLs; map ws(s) schemes for convenience.
            val normalized = replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
            return normalized.toHttpUrlOrNull() ?: throw IllegalArgumentException("bad url")
        }
    }
}

package com.mdc159.sierra.phone

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.ChatMessage
import com.mdc159.sierra.voice.convo.ConversationState
import com.mdc159.sierra.voice.convo.EndReason
import java.net.URI
import java.util.UUID

/** Which job owns the screen right now. Only one runs at a time. */
enum class PhoneJob { NONE, VOICE, TEXT, CHECK }

/**
 * Prevents overlapping voice, text and connection-check jobs. [begin] fails unless the
 * screen is idle, so a text send can never start while the microphone conversation runs
 * and vice versa. Thread-safe: callers are lifecycle coroutines and button handlers.
 */
class PhoneJobGate {
    private val lock = Any()

    var active: PhoneJob = PhoneJob.NONE
        private set

    fun begin(job: PhoneJob): Boolean = synchronized(lock) {
        if (active != PhoneJob.NONE) {
            false
        } else {
            active = job
            true
        }
    }

    /** Ends [job] only if it is the one running; a no-op otherwise. */
    fun end(job: PhoneJob): Boolean = synchronized(lock) {
        if (active == job) {
            active = PhoneJob.NONE
            true
        } else {
            false
        }
    }
}

/**
 * Short rolling conversation for the phone. Unlike the truck's driving prompt, this one
 * does not assume the owner is driving: the phone is used on and off the truck.
 *
 * [id] is the stable identity sent to the Sierra bridge (`X-Sierra-Conversation`) so equal
 * short replies on Victoria cannot be matched into the wrong session. It rotates whenever
 * the context is cleared.
 */
class PhoneConversation(private val maxTurns: Int = 12) {

    private val turns = ArrayDeque<ChatMessage>()

    @Volatile
    var id: String = UUID.randomUUID().toString()
        private set

    @Synchronized
    fun messagesFor(userText: String): List<ChatMessage> =
        listOf(ChatMessage("system", SYSTEM_PROMPT)) + turns + ChatMessage("user", userText)

    @Synchronized
    fun record(userText: String, reply: String) {
        turns.addLast(ChatMessage("user", userText))
        turns.addLast(ChatMessage("assistant", reply))
        while (turns.size > maxTurns * 2) turns.removeFirst()
    }

    @Synchronized
    fun clear() {
        turns.clear()
        id = UUID.randomUUID().toString()
    }

    @Synchronized
    fun size(): Int = turns.size / 2

    companion object {
        const val SYSTEM_PROMPT =
            "You are Sierra, the owner's assistant, and this conversation is from their phone. " +
                "The owner may be away from the truck; do not assume they are driving. " +
                "Your replies are spoken aloud: answer in one to three short conversational sentences " +
                "of plain text, with no lists, no markdown and no URLs. If a full answer needs more, " +
                "give the key point and offer to continue."
    }
}

/** The readable You/Sierra lines the screen keeps, capped so a long chat stays cheap. */
class TranscriptLog(private val maxLines: Int = 300) {

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun user(text: String) = add("You: ${text.trim()}")

    @Synchronized
    fun agent(text: String) = add("Sierra: ${text.trim()}")

    /** A separator such as "Switched to hermes on the Sierra bridge". */
    @Synchronized
    fun event(text: String) = add("— $text")

    private fun add(line: String) {
        if (line.isBlank()) return
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun render(): String = lines.joinToString(separator = "\n")

    @Synchronized
    fun clear() = lines.clear()
}

/** One status line per conversation state, in the truck screen's terms. */
object PhoneStatus {
    const val IDLE =
        "Ready. Tap Talk and speak — say \"stop\" to finish. Or type a message below."

    fun forState(s: ConversationState): String = when (s) {
        is ConversationState.Idle -> IDLE
        is ConversationState.Listening -> if (s.turn <= 1) "Listening…" else "Listening… (say \"stop\" to finish)"
        is ConversationState.Thinking -> if (s.heard == null) "Transcribing…" else "Thinking…"
        is ConversationState.Speaking -> "Speaking… tap Interrupt to jump in."
        is ConversationState.Ended -> forEnd(s.reason, s.message)
    }

    fun forEnd(reason: EndReason, message: String?): String = when (reason) {
        EndReason.STOP_PHRASE -> "Ended — you said stop."
        EndReason.SILENCE -> "Went quiet. Tap Talk to keep talking."
        EndReason.HOST_ENDED -> "The microphone ended. Tap Talk to keep talking."
        EndReason.USER_STOPPED -> "Stopped."
        EndReason.TURN_COMPLETE -> "Tap Talk to keep talking."
        EndReason.ERROR -> "Error: ${message ?: "Something went wrong."}"
    }
}

/**
 * Tailscale-related hints and labels. Hostnames only; never credentials. The Hermes agent
 * lives on Victoria behind the Sierra bridge, reached over the owner's tailnet, so a
 * tailnet-looking host that fails usually means Tailscale is down, not the agent.
 */
object Tailnet {
    const val PACKAGE = "com.tailscale.ipn"

    /**
     * The agent line while the effective settings are still being resolved (typed values
     * plus the Studio 54 vault overlay, refreshed when stale). A neutral loading state:
     * the real label follows once loading finishes.
     */
    const val CHECKING = "Checking connection…"

    private val CGNAT = Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d{1,3}\\.\\d{1,3}$")
    private val PRIVATE_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$")

    fun host(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    /** True when [url]'s host is a tailnet/CGNAT or LAN address that needs a VPN or local network. */
    fun looksPrivate(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "localhost" ||
            h.endsWith(".local") ||
            h.endsWith(".internal") ||
            h.endsWith(".ts.net") ||
            CGNAT.matches(h) ||
            h.startsWith("10.") ||
            h.startsWith("192.168.") ||
            PRIVATE_172.matches(h)
    }

    /** The Tailscale hint shown under the agent line; null when it does not apply. */
    fun hint(config: AgentConfig): String? {
        if (!config.usesBridge) return null
        val url = config.chatBaseUrl
        if (url.isBlank() || !looksPrivate(url)) return null
        return "Keep Tailscale connected for this private agent."
    }

    /** What the header shows for who answers. Model name and host only; never a key or token. */
    fun agentLabel(config: AgentConfig): String = when {
        !config.isConfigured && config.usesBridge ->
            "Not connected yet — open Settings and use Connect to Hermes on Victoria."
        !config.isConfigured ->
            "No agent configured — open Settings and set a base URL and model."
        config.usesBridge -> {
            val h = host(config.chatBaseUrl) ?: "invalid endpoint"
            "Agent: ${config.chatModel} on the Sierra bridge ($h)"
        }
        else -> {
            val h = host(config.chatBaseUrl) ?: "invalid endpoint"
            "Agent: ${config.chatModel} ($h)"
        }
    }
}

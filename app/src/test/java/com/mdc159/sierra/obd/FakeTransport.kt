package com.mdc159.sierra.obd

import java.io.IOException

/**
 * Scripted ELM327 stand-in. Each written command (without the trailing CR) is looked
 * up in [replies]; the reply text is queued for the next reads. Unknown commands get
 * `?`, like a real adapter. Replies never include the `>` prompt unless [appendPrompt]
 * is false, so callers script just the body.
 */
class FakeTransport(
    private val replies: MutableMap<String, String> = mutableMapOf(),
    private val appendPrompt: Boolean = true,
) : ObdTransport {
    val sent = mutableListOf<String>()
    private val pending = StringBuilder()
    private var closed = false
    private var pendingWrite = StringBuilder()

    /** Commands that produce no reply at all, so the client has to time out. */
    val silent = mutableSetOf<String>()

    /** Reply text queued without a command, e.g. a late answer after a timeout. */
    fun inject(text: String) {
        pending.append(text)
    }

    fun on(command: String, reply: String) {
        replies[ObdSafety.normalize(command)] = reply
    }

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("closed")
        pendingWrite.append(String(bytes, Charsets.US_ASCII))
        while (true) {
            val cr = pendingWrite.indexOf("\r")
            if (cr < 0) return
            val command = pendingWrite.substring(0, cr)
            pendingWrite.delete(0, cr + 1)
            sent += command
            if (command in silent) continue
            val body = replies[ObdSafety.normalize(command)] ?: "?"
            pending.append(body)
            if (appendPrompt) pending.append("\r\r>")
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        if (closed) return -1
        if (pending.isEmpty()) return 0
        val n = minOf(buffer.size, pending.length)
        for (i in 0 until n) buffer[i] = pending[i].code.toByte()
        pending.delete(0, n)
        return n
    }

    override fun close() {
        closed = true
    }

    companion object {
        /** A transport already scripted to pass [Elm327Client.initialize]. */
        fun initialized(): FakeTransport = FakeTransport().apply {
            on("ATZ", "\r\rELM327 v1.5")
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6", "ATSH7DF", "ATSH7E0", "ATSH7E2")) {
                on(c, "OK")
            }
            on("ATDPN", "6")
            on("0100", "4100BE3EA813")
        }
    }
}

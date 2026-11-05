package com.mdc159.sierra.obd

/**
 * Turns the raw text an ELM327 prints before its `>` prompt into OBD messages.
 *
 * Assumes the client's setup: echo off, spaces off, headers off. Handles the CAN
 * multi-frame layout the ELM327 prints with CAN auto-formatting on:
 *
 * ```
 * 014
 * 0:490201314743
 * 1:5431324533374A
 * 2:35393337313131
 * ```
 */
object ElmResponseParser {

    /** Adapter messages that mean "no usable answer". */
    private val ERRORS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY",
        "DATA ERROR", "BUFFER FULL", "FB ERROR", "LV RESET", "STOPPED", "ERR",
        "ACT ALERT", "?",
    )

    private val HEX = Regex("[0-9A-F]+")
    private val MULTI_LENGTH = Regex("[0-9A-F]{3}")
    private val MULTI_FRAME = Regex("([0-9A-F]):([0-9A-F]*)")

    /** Splits [raw] into trimmed, non-empty lines, dropping status chatter and an echoed [command]. */
    fun lines(raw: String, command: String? = null): List<String> {
        val echo = command?.let { ObdSafety.normalize(it) }
        return raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("SEARCHING")) it.removePrefix("SEARCHING...").trim() else it }
            .map {
                when {
                    !it.startsWith("BUS INIT") -> it
                    it.contains("OK") -> it.substringAfter("OK").trim()
                    else -> "BUS INIT ERROR"
                }
            }
            .filter { it.isNotEmpty() }
            .filter { echo == null || ObdSafety.normalize(it) != echo }
    }

    /** Returns the adapter error in [lines], if the whole reply is one. */
    fun errorIn(lines: List<String>): String? {
        if (lines.isEmpty()) return "empty reply"
        val hasData = lines.any { HEX.matches(it.replace(" ", "")) || MULTI_FRAME.matches(it.replace(" ", "")) }
        if (hasData) return null
        return lines.firstOrNull { line -> ERRORS.any { line.uppercase().startsWith(it) } } ?: lines.first()
    }

    /** Reassembles hex lines into one byte array per message. */
    fun messages(lines: List<String>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pendingLength = -1
        val pending = StringBuilder()

        fun flush() {
            if (pendingLength >= 0 && pending.isNotEmpty()) {
                val hex = pending.toString()
                val wanted = minOf(pendingLength * 2, hex.length - hex.length % 2)
                out += hexToBytes(hex.substring(0, wanted))
            }
            pendingLength = -1
            pending.setLength(0)
        }

        for (rawLine in lines) {
            val line = rawLine.replace(" ", "").uppercase()
            val frame = MULTI_FRAME.matchEntire(line)
            when {
                frame != null && pendingLength >= 0 -> {
                    pending.append(frame.groupValues[2])
                    if (pending.length >= pendingLength * 2) flush()
                }
                MULTI_LENGTH.matches(line) -> {
                    flush()
                    pendingLength = line.toInt(16)
                }
                HEX.matches(line) && line.length % 2 == 0 -> {
                    flush()
                    out += hexToBytes(line)
                }
                else -> Unit // Status text or a fragment we can't place; skip it.
            }
        }
        flush()
        return out
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length: $hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

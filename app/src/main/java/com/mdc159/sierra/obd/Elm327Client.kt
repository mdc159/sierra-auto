package com.mdc159.sierra.obd

import java.io.IOException

/** Timeouts and protocol choice for [Elm327Client]. */
data class Elm327Config(
    /** Normal command deadline. A healthy CAN reply takes well under 200 ms. */
    val commandTimeoutMillis: Long = 2_000,
    /** `ATZ` reboots the chip; clones can take a couple of seconds. */
    val resetTimeoutMillis: Long = 5_000,
    /** The first OBD request after `ATSP0` triggers a protocol search, which can take ~10 s. */
    val searchTimeoutMillis: Long = 12_000,
    /** How long to wait for a stray prompt when resynchronising after a timeout. */
    val resyncTimeoutMillis: Long = 500,
    /**
     * `ATSP` argument. `6` is ISO 15765-4 CAN 11-bit 500 kbaud, which every 2008+ GM
     * truck uses, and skips the slow search. `0` means automatic.
     */
    val protocol: String = "6",
    /** Upper bound on a single read slice, so the deadline is checked regularly. */
    val pollSliceMillis: Long = 100,
)

/** What `ATZ`/`ATI` reported, plus the protocol the adapter settled on. */
data class Elm327Info(val identity: String, val protocol: String)

/**
 * Explicit opt-in for Mode 04. Both flags must be true, and the client also checks
 * that the truck reports 0 km/h before it sends anything.
 */
data class ClearCodesRequest(val userConfirmed: Boolean, val vehicleStopped: Boolean)

/**
 * A blocking, read-only ELM327 client.
 *
 * Call from a background thread. Every command passes through [ObdSafety] and has a
 * deadline; nothing waits forever. One command is in flight at a time.
 *
 * Not wired into any screen yet.
 */
class Elm327Client(
    private val transport: ObdTransport,
    private val config: Elm327Config = Elm327Config(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var needsResync = false
    private var currentHeader = "7DF"
    private var searched = false

    /**
     * Resets the adapter and applies the read-only setup:
     * `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP<protocol>`, then `0100`
     * to prove the bus answers.
     */
    fun initialize(): Elm327Info = synchronized(lock) {
        val reset = command("ATZ", config.resetTimeoutMillis)
        val identity = ElmResponseParser.lines(reset).lastOrNull { it.contains("ELM", ignoreCase = true) }
            ?: ElmResponseParser.lines(reset).lastOrNull().orEmpty()
        for (setup in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP${config.protocol}")) {
            expectOk(setup)
        }
        currentHeader = "7DF"
        searched = false
        query(0x01, byteArrayOf(0x00))
        val protocol = ElmResponseParser.lines(command("ATDPN", config.commandTimeoutMillis)).firstOrNull().orEmpty()
        Elm327Info(identity = identity, protocol = protocol)
    }

    /** Reads one Mode 01 PID from the first ECU that answers. */
    fun <T> read(pid: ObdPid<T>): T = pid.decode(readPidBytes(pid.pid))

    /** Raw data bytes for Mode 01 [pid], after the `41 <pid>` echo. */
    fun readPidBytes(pid: Int): ByteArray {
        require(pid in 0..0xFF) { "PID must be one byte" }
        val replies = query(0x01, byteArrayOf(pid.toByte()))
        val match = replies.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0xFF) == pid }
            ?: throw ObdParseException("No reply echoed PID 0x%02X".format(pid))
        return match.copyOfRange(1, match.size)
    }

    /** Every Mode 01 PID any ECU says it supports, walking 0x00, 0x20, 0x40, ... */
    fun supportedPids(): Set<Int> {
        val result = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val replies = try {
                query(0x01, byteArrayOf(base.toByte()))
            } catch (e: ObdAdapterException) {
                break
            }
            replies.filter { it.size >= 5 && (it[0].toInt() and 0xFF) == base }
                .forEach { result += ObdPids.decodeSupported(base, it.copyOfRange(1, 5)) }
            if ((base + 0x20) !in result) break
            base += 0x20
        }
        return result
    }

    /** Confirmed codes (Mode 03), merged across every ECU that answered. */
    fun readStoredCodes(): List<Dtc> = readCodes(0x03)

    /** Pending codes (Mode 07): faults seen once that have not matured yet. */
    fun readPendingCodes(): List<Dtc> = readCodes(0x07)

    /** Permanent codes (Mode 0A): survive a clear until the monitor passes again. */
    fun readPermanentCodes(): List<Dtc> = readCodes(0x0A)

    private fun readCodes(service: Int): List<Dtc> {
        val replies = try {
            query(service, ByteArray(0))
        } catch (e: ObdAdapterException) {
            if (e.reason.startsWith("NO DATA")) return emptyList() else throw e
        }
        return replies.flatMap { Dtc.parseList(it) }.distinct()
    }

    /** VIN from Mode 09 PID 02. */
    fun readVin(): String {
        val reply = query(0x09, byteArrayOf(0x02)).firstOrNull()
            ?: throw ObdParseException("No VIN reply")
        // 02 <count> <17 ASCII bytes>; some ECUs pad with leading zeros.
        val ascii = reply.drop(2).map { (it.toInt() and 0xFF).toChar() }.filter { it in '0'..'9' || it in 'A'..'Z' }
        return ascii.joinToString("").takeLast(17)
    }

    /**
     * Mode 04: clears stored codes and freeze frame and resets readiness monitors.
     * This is the only command that changes ECU state. See docs/OBD2-PLAN.md.
     */
    fun clearDiagnosticCodes(request: ClearCodesRequest) = synchronized(lock) {
        if (!request.userConfirmed || !request.vehicleStopped) {
            throw ObdCommandBlockedException("04", "clearing needs explicit confirmation and a stopped truck")
        }
        val speed = read(ObdPids.VEHICLE_SPEED)
        if (speed > 0.0) throw ObdCommandBlockedException("04", "truck reports $speed km/h; stop first")
        setHeader("7DF")
        val raw = command("04", config.commandTimeoutMillis, allowClearCodes = true)
        val lines = ElmResponseParser.lines(raw, "04")
        ElmResponseParser.errorIn(lines)?.let { throw ObdAdapterException("04", it) }
        val ok = ElmResponseParser.messages(lines).any { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x44 }
        if (!ok) throw ObdParseException("No 44 acknowledgement for clear")
    }

    /**
     * Reads a GM Mode 22 DID from the module at [pid]'s header, then points the
     * adapter back at the functional address. Results from unverified PIDs must be
     * labelled as such by the caller.
     */
    fun <T> readExtended(pid: GmExtendedPid<T>): T = synchronized(lock) {
        try {
            setHeader(pid.header)
            val did = byteArrayOf((pid.did ushr 8).toByte(), pid.did.toByte())
            val replies = query(0x22, did)
            val match = replies.firstOrNull {
                it.size >= 2 && it[0] == did[0] && it[1] == did[1]
            } ?: throw ObdParseException("No reply echoed DID 0x%04X".format(pid.did))
            pid.decode(match.copyOfRange(2, match.size))
        } finally {
            if (!needsResync) runCatching { setHeader("7DF") }
        }
    }

    /**
     * Sends a service request and returns each positive reply without its
     * response-service byte. Throws on adapter errors and negative responses.
     */
    fun query(service: Int, data: ByteArray): List<ByteArray> = synchronized(lock) {
        val request = buildString {
            append("%02X".format(service))
            data.forEach { append("%02X".format(it.toInt() and 0xFF)) }
        }
        val timeout = if (searched) config.commandTimeoutMillis else config.searchTimeoutMillis
        val raw = command(request, timeout)
        searched = true
        val lines = ElmResponseParser.lines(raw, request)
        ElmResponseParser.errorIn(lines)?.let { throw ObdAdapterException(request, it) }
        val messages = ElmResponseParser.messages(lines)
        val positive = messages.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == service + 0x40 }
        if (positive.isEmpty()) {
            val negative = messages.firstOrNull {
                it.size >= 3 && (it[0].toInt() and 0xFF) == 0x7F && (it[1].toInt() and 0xFF) == service &&
                    (it[2].toInt() and 0xFF) != 0x78 // 0x78 = "response pending", not final
            }
            if (negative != null) throw ObdNegativeResponseException(service, negative[2].toInt() and 0xFF)
            throw ObdParseException("No positive reply to $request in '${raw.trim()}'")
        }
        positive.map { it.copyOfRange(1, it.size) }
    }

    private fun setHeader(header: String) {
        if (header == currentHeader) return
        expectOk("ATSH$header")
        currentHeader = header
    }

    private fun expectOk(cmd: String) {
        val lines = ElmResponseParser.lines(command(cmd, config.commandTimeoutMillis), cmd)
        if (lines.none { it.equals("OK", ignoreCase = true) }) {
            throw ObdAdapterException(cmd, lines.joinToString(" ").ifEmpty { "no OK" })
        }
    }

    /**
     * Sends one checked command and returns everything before the `>` prompt.
     *
     * Never sends a bare carriage return: on an ELM327 that repeats the previous
     * command, which could repeat a Mode 04.
     */
    private fun command(cmd: String, timeoutMillis: Long, allowClearCodes: Boolean = false): String {
        ObdSafety.requireAllowed(cmd, allowClearCodes)
        val normalized = ObdSafety.normalize(cmd)
        if (needsResync) resync()
        discardPending()
        try {
            transport.write((normalized + "\r").toByteArray(Charsets.US_ASCII))
        } catch (e: IOException) {
            throw ObdDisconnectedException("Write failed: ${e.message}")
        }
        return readUntilPrompt(normalized, timeoutMillis)
    }

    private fun readUntilPrompt(cmd: String, timeoutMillis: Long): String {
        val deadline = nanoTime() + timeoutMillis * 1_000_000
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (true) {
            val remainingMillis = (deadline - nanoTime()) / 1_000_000
            if (remainingMillis <= 0) {
                needsResync = true
                throw ObdTimeoutException(cmd, timeoutMillis, sb.toString())
            }
            val n = try {
                transport.read(buf, minOf(remainingMillis, config.pollSliceMillis))
            } catch (e: IOException) {
                throw ObdDisconnectedException("Read failed: ${e.message}")
            }
            if (n < 0) throw ObdDisconnectedException("Adapter closed the link during '$cmd'")
            if (n == 0) continue
            for (i in 0 until n) {
                val ch = (buf[i].toInt() and 0xFF).toChar()
                if (ch == '>') return sb.toString()
                if (ch != '\u0000') sb.append(ch)
            }
        }
    }

    /** After a timeout the adapter may still print a late reply and prompt; swallow them. */
    private fun resync() {
        needsResync = false
        try {
            readUntilPrompt("<resync>", config.resyncTimeoutMillis)
        } catch (e: ObdTimeoutException) {
            needsResync = false // Nothing came; the adapter is idle.
        }
    }

    /** Drops any bytes already waiting so they can't be mistaken for this reply. */
    private fun discardPending() {
        val buf = ByteArray(256)
        while (true) {
            val n = try {
                transport.read(buf, 0)
            } catch (e: IOException) {
                throw ObdDisconnectedException("Read failed: ${e.message}")
            }
            if (n <= 0) return
        }
    }
}

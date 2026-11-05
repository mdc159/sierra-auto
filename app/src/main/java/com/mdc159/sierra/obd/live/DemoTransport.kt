package com.mdc159.sierra.obd.live

import com.mdc159.sierra.obd.ObdSafety
import com.mdc159.sierra.obd.ObdTransport
import java.io.IOException
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A pretend ELM327 wired to a pretend 6.2L V8, for testing without hardware.
 *
 * It speaks enough of the ELM327 protocol for [com.mdc159.sierra.obd.Elm327Client]:
 * the AT setup commands, `ATRV`, Mode 01 live data with supported-PID bitmaps, Mode
 * 03/07/0A codes (two stored codes, nothing pending) and the Mode 09 VIN. Values come
 * from [DemoEngine], which runs a slow idle, accelerate, cruise, slow-down cycle and warms
 * the coolant up over the first few minutes, so a screen refreshing every second visibly
 * changes. Echo, spaces and linefeeds follow the real chip's defaults so the parser's
 * handling of them is exercised too.
 *
 * Everything goes through [ObdSafety] like a real adapter would not, which keeps the
 * demo honest: a blocked command gets `?` instead of a reply.
 */
class DemoTransport(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Simulated seconds already on the engine when the transport is created. */
    private val startOffsetSeconds: Double = 0.0,
) : ObdTransport {

    private val startMillis = clock()
    private val engine = DemoEngine()
    private val pendingWrite = StringBuilder()
    private val inbox = StringBuilder()

    @Volatile
    private var closed = false

    private var echo = true
    private var spaces = true
    private var linefeeds = true
    private var headersOn = false
    private var protocol = "6"
    private var header = "7DF"

    /** Every command the client sent, without the trailing CR, for assertions. */
    val sent = mutableListOf<String>()

    private val elapsedSeconds: Double
        get() = startOffsetSeconds + (clock() - startMillis) / 1000.0

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("demo transport closed")
        pendingWrite.append(String(bytes, Charsets.US_ASCII))
        while (true) {
            val cr = pendingWrite.indexOf("\r")
            if (cr < 0) return
            val command = pendingWrite.substring(0, cr)
            pendingWrite.delete(0, cr + 1)
            sent += command
            if (echo) inbox.append(command).append(eol())
            val reply = handle(ObdSafety.normalize(command))
            inbox.append(reply).append(eol()).append(eol()).append('>')
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        if (closed) return -1
        if (inbox.isEmpty()) return 0
        val n = minOf(buffer.size, inbox.length)
        for (i in 0 until n) buffer[i] = inbox[i].code.toByte()
        inbox.delete(0, n)
        return n
    }

    override fun close() {
        closed = true
    }

    private fun eol() = if (linefeeds) "\r\n" else "\r"

    private fun handle(c: String): String {
        if (c.isEmpty()) return sent.getOrNull(sent.size - 2)?.let { handle(ObdSafety.normalize(it)) } ?: "?"
        if (c.startsWith("AT")) return handleAt(c.removePrefix("AT"))
        if (ObdSafety.blockReason(c) != null) return "?"
        return handleObd(c)
    }

    private fun handleAt(at: String): String = when {
        at == "Z" -> {
            echo = true; spaces = true; linefeeds = true; headersOn = false; protocol = "6"; header = "7DF"
            "\r\r$BANNER"
        }
        at == "WS" || at == "D" -> { echo = true; spaces = true; headersOn = false; header = "7DF"; "OK" }
        at == "I" -> BANNER
        at == "@1" -> "Sierra demo adapter"
        at == "RV" -> String.format(java.util.Locale.US, "%.1fV", engine.snapshot(elapsedSeconds).voltage)
        at == "E0" -> { echo = false; "OK" }
        at == "E1" -> { echo = true; "OK" }
        at == "L0" -> { linefeeds = false; "OK" }
        at == "L1" -> { linefeeds = true; "OK" }
        at == "S0" -> { spaces = false; "OK" }
        at == "S1" -> { spaces = true; "OK" }
        at == "H0" -> { headersOn = false; "OK" }
        at == "H1" -> { headersOn = true; "OK" }
        at == "DP" -> if (protocol == "0") "AUTO, ISO 15765-4 (CAN 11/500)" else "ISO 15765-4 (CAN 11/500)"
        at == "DPN" -> if (protocol == "0") "A6" else protocol
        at.startsWith("SP") -> { protocol = at.removePrefix("SP"); "OK" }
        at.startsWith("SH") -> { header = at.removePrefix("SH"); "OK" }
        at.startsWith("ST") || at.startsWith("AT") || at.startsWith("CAF") -> "OK"
        else -> "?"
    }

    private fun handleObd(c: String): String {
        val service = c.substring(0, 2).toInt(16)
        val data = c.substring(2)
        // Only the engine answers physical requests to 7E0; the TCM has no J1979 data here.
        if (header == "7E1" || header == "7E2") return if (service == 0x22) "7F2231" else "NO DATA"
        return when (service) {
            0x01 -> handleMode01(data)
            0x03 -> frame("43" + STORED_CODES)
            0x07 -> frame("4700")
            0x0A -> frame("4A00")
            0x09 -> if (data == "02") vinReply() else "NO DATA"
            0x22 -> "7F2211"
            else -> "?"
        }
    }

    private fun handleMode01(data: String): String {
        val pids = data.chunked(2).map { it.toInt(16) }
        val payloads = pids.map { pid -> encodePid(pid) ?: return "NO DATA" }
        val body = "41" + pids.indices.joinToString("") { "%02X".format(pids[it]) + payloads[it] }
        return if (body.length <= 14) frame(body) else multiFrame(body)
    }

    private fun encodePid(pid: Int): String? {
        val s = engine.snapshot(elapsedSeconds)
        return when (pid) {
            0x00 -> "9E3F8003"
            0x01 -> "8207E500" // MIL on, 2 codes
            0x04 -> b1(s.loadPct * 255.0 / 100.0)
            0x05 -> b1(s.coolantC + 40)
            0x06 -> b1(128 + s.shortTrimPct * 128.0 / 100.0)
            0x07 -> b1(128 + s.longTrimPct * 128.0 / 100.0)
            0x0B -> b1(s.mapKpa)
            0x0C -> b2(s.rpm * 4)
            0x0D -> b1(s.speedKmh)
            0x0E -> b1((s.timingAdvance + 64) * 2)
            0x0F -> b1(s.intakeC + 40)
            0x10 -> b2(s.mafGs * 100)
            0x11 -> b1(s.throttlePct * 255.0 / 100.0)
            0x1F -> b2(s.runTimeS)
            0x20 -> "8002A001"
            0x21 -> b2(120.0)
            0x2F -> b1(s.fuelPct * 255.0 / 100.0)
            0x31 -> b2(1480.0)
            0x33 -> b1(98.0)
            0x40 -> "64000014"
            0x42 -> b2(s.voltage * 1000)
            0x43 -> b2(s.absoluteLoadPct * 255.0 / 100.0)
            0x46 -> b1(24.0 + 40)
            0x5C -> b1(s.oilC + 40)
            0x5E -> b2(s.fuelRateLh * 20)
            else -> null
        }
    }

    private fun b1(v: Double) = "%02X".format(v.roundToInt().coerceIn(0, 255))
    private fun b2(v: Double) = "%04X".format(v.roundToInt().coerceIn(0, 65535))

    /** One CAN frame the way the chip prints it with the current formatting settings. */
    private fun frame(hex: String): String {
        val prefix = if (headersOn) "7E8" + "%02X".format(hex.length / 2) else ""
        val all = prefix + hex
        return if (spaces) all.chunked(2).joinToString(" ") else all
    }

    private fun multiFrame(hex: String): String {
        val total = hex.length / 2
        val lines = mutableListOf("%03X".format(total))
        var rest = hex
        var index = 0
        var take = 12 // 6 bytes in the first frame, 7 in the rest
        while (rest.isNotEmpty()) {
            val chunk = rest.take(take)
            rest = rest.drop(take)
            lines += "$index:" + (if (spaces) chunk.chunked(2).joinToString(" ") else chunk)
            index = (index + 1) % 16
            take = 14
        }
        return lines.joinToString(eol())
    }

    private fun vinReply(): String {
        val ascii = VIN.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }
        return multiFrame("490201$ascii")
    }

    companion object {
        const val BANNER = "ELM327 v1.5 DEMO"
        const val VIN = "SIERRADEMO0000001"

        /** Mode 03 payload: count 2, then P0420 and P0171. */
        const val STORED_CODES = "0204200171"
        val DEMO_CODES = listOf("P0420", "P0171")
    }
}

/**
 * The simulated engine. Pure function of time so it is deterministic in tests.
 *
 * A 90 s drive cycle: 20 s idle, 15 s accelerating, 30 s cruising at about 65 mph,
 * 15 s slowing, 10 s idle. The coolant warms from about 62 °C to 92 °C over the first
 * three minutes. Fuel drops slowly. Small sine ripples keep every value moving.
 */
class DemoEngine {
    data class Snapshot(
        val rpm: Double,
        val speedKmh: Double,
        val coolantC: Double,
        val intakeC: Double,
        val loadPct: Double,
        val absoluteLoadPct: Double,
        val fuelPct: Double,
        val voltage: Double,
        val throttlePct: Double,
        val mapKpa: Double,
        val mafGs: Double,
        val timingAdvance: Double,
        val runTimeS: Double,
        val oilC: Double,
        val fuelRateLh: Double,
        val shortTrimPct: Double,
        val longTrimPct: Double,
        /** 0.0 at idle, 1.0 at cruise. */
        val drive: Double,
    )

    fun drive(t: Double): Double {
        val phase = ((t % CYCLE_SECONDS) + CYCLE_SECONDS) % CYCLE_SECONDS
        return when {
            phase < 20 -> 0.0
            phase < 35 -> smooth((phase - 20) / 15)
            phase < 65 -> 1.0
            phase < 80 -> 1 - smooth((phase - 65) / 15)
            else -> 0.0
        }
    }

    fun snapshot(t: Double): Snapshot {
        val d = drive(t)
        val warm = 1 - exp(-t / 180.0)
        return Snapshot(
            rpm = 750 + 1150 * d + 25 * sin(t * 1.3) + 10 * sin(t * 0.37),
            speedKmh = 105 * d,
            coolantC = 62 + 30 * warm + 0.8 * sin(t / 13),
            intakeC = 27 + 3 * sin(t / 47) + 4 * d,
            loadPct = 18 + 50 * d + 3 * sin(t * 0.7),
            absoluteLoadPct = 20 + 45 * d,
            fuelPct = (61.5 - t / 900).coerceAtLeast(5.0),
            voltage = 14.15 + 0.08 * sin(t / 5) - 0.1 * d,
            throttlePct = 12 + 45 * d,
            mapKpa = 35 + 55 * d,
            mafGs = 3 + 25 * d,
            timingAdvance = 12 + 18 * d,
            runTimeS = t,
            oilC = 60 + 35 * warm + 4 * d,
            fuelRateLh = 1.2 + 14 * d,
            shortTrimPct = 1.5 * sin(t * 2.1),
            longTrimPct = 2.3,
            drive = d,
        )
    }

    private fun smooth(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    companion object {
        const val CYCLE_SECONDS = 90.0
    }
}

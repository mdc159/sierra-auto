package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.ElmResponseParser
import com.mdc159.sierra.obd.ObdParseException

/**
 * Turns the raw text an ELM327 printed for a `22 <did>` request into a [GmReading].
 *
 * Built on [ElmResponseParser], so it accepts the same shapes: echo, `SEARCHING...`,
 * spaced or unspaced hex, several modules answering, and the `0:`/`1:` multi-frame
 * layout. Nothing here touches the adapter; it is the piece that can be tested
 * against scripted replies without a client.
 */
object GmResponseDecoder {

    private const val POSITIVE = 0x62
    private const val NEGATIVE = 0x7F
    private const val SERVICE = 0x22
    private const val PENDING = 0x78

    /** Decodes [raw] (everything before the `>` prompt) as the answer to [did] from [module]. */
    fun <T> decode(did: GmDid<T>, raw: String, module: GmModule = did.module): GmReading<T> {
        val lines = ElmResponseParser.lines(raw, did.request)
        ElmResponseParser.errorIn(lines)?.let { return GmReading.NoReply(it, module) }
        return fromMessages(did, ElmResponseParser.messages(lines), module)
    }

    /** The same, starting from already reassembled messages (each with its service byte). */
    fun <T> fromMessages(did: GmDid<T>, messages: List<ByteArray>, module: GmModule): GmReading<T> {
        if (messages.isEmpty()) return GmReading.NoReply("empty reply", module)

        val echoed = messages.filter { it.size >= 3 && b(it, 0) == POSITIVE && b(it, 1) == did.didHigh && b(it, 2) == did.didLow }
        if (echoed.isNotEmpty()) {
            val data = echoed.first().copyOfRange(3, echoed.first().size)
            return try {
                GmReading.Value(did.decode(data), module, data.map { it.toInt() and 0xFF })
            } catch (e: ObdParseException) {
                GmReading.Malformed("short frame: ${e.message}", module)
            }
        }

        val negatives = messages.filter { it.size >= 3 && b(it, 0) == NEGATIVE && b(it, 1) == SERVICE }
        val final = negatives.firstOrNull { b(it, 2) != PENDING }
        if (final != null) return GmReading.NegativeResponse(b(final, 2), module)
        if (negatives.isNotEmpty()) {
            return GmReading.NoReply("module said response pending (7F 22 78) and never finished", module)
        }

        val otherPositive = messages.firstOrNull { it.isNotEmpty() && b(it, 0) == POSITIVE }
        if (otherPositive != null) {
            return if (otherPositive.size < 3) {
                GmReading.Malformed("short frame: 62 without a full DID echo (${hex(otherPositive)})", module)
            } else {
                GmReading.Malformed(
                    "reply echoed DID 0x%02X%02X, expected 0x%04X".format(b(otherPositive, 1), b(otherPositive, 2), did.did),
                    module,
                )
            }
        }
        return GmReading.Malformed("no 62 reply among: ${messages.joinToString(" / ") { hex(it) }}", module)
    }

    private fun b(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

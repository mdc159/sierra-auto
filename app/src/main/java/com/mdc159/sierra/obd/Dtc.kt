package com.mdc159.sierra.obd

/** A diagnostic trouble code such as `P0420`. */
data class Dtc(val code: String) {
    override fun toString(): String = code

    companion object {
        private val LETTERS = charArrayOf('P', 'C', 'B', 'U')

        /** Decodes the two-byte J1979 encoding. Returns `null` for the `0000` filler. */
        fun fromBytes(high: Int, low: Int): Dtc? {
            val a = high and 0xFF
            val b = low and 0xFF
            if (a == 0 && b == 0) return null
            val letter = LETTERS[a ushr 6]
            val first = (a ushr 4) and 0x03
            val second = a and 0x0F
            return Dtc("%c%d%X%02X".format(letter, first, second, b))
        }

        /**
         * Parses the payload of a Mode 03/07/0A reply, without the `43`/`47`/`4A` byte.
         *
         * On CAN (the Sierra's bus) the payload starts with a count byte, so its length
         * is odd. Older protocols send bare pairs, so the length is even.
         */
        fun parseList(payload: ByteArray): List<Dtc> {
            val bytes = if (payload.size % 2 == 1) payload.copyOfRange(1, payload.size) else payload
            val out = mutableListOf<Dtc>()
            var i = 0
            while (i + 1 < bytes.size) {
                fromBytes(bytes[i].toInt(), bytes[i + 1].toInt())?.let { out += it }
                i += 2
            }
            return out
        }
    }
}

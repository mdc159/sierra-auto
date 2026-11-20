package com.mdc159.sierra.voice.tts

/**
 * Splits a reply into sentence-sized chunks for streamed text-to-speech.
 *
 * The first chunk is the first sentence on its own, so the first audio arrives as early
 * as possible. Later sentences are packed together up to [maxChars] so a long reply does
 * not turn into dozens of tiny requests. A sentence longer than [maxChars] is cut at a
 * clause boundary (comma, semicolon, colon, dash) or, failing that, at a word boundary.
 */
object SentenceChunker {

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "st", "mt", "ft", "vs", "etc", "e.g", "i.e", "no", "jr", "sr", "inc", "ltd", "u.s", "a.m", "p.m",
    )

    fun chunks(text: String, maxChars: Int = 220): List<String> {
        val sentences = sentences(text)
        if (sentences.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val first = sentences.first()
        out += split(first, maxChars)
        val current = StringBuilder()
        for (sentence in sentences.drop(1)) {
            for (piece in split(sentence, maxChars)) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                    out += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Sentence boundaries: end punctuation followed by whitespace, or a line break. */
    fun sentences(text: String): List<String> {
        val normalized = text.replace(Regex("[ \\t]+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c == '\n' || c == '\r') {
                flush(current, out)
                i++
                continue
            }
            current.append(c)
            if (c in ".!?…" && isBoundary(normalized, i)) {
                // Swallow closing quotes or brackets that belong to this sentence.
                var j = i + 1
                while (j < normalized.length && normalized[j] in "\"'”’)]") {
                    current.append(normalized[j])
                    j++
                }
                flush(current, out)
                i = j
                continue
            }
            i++
        }
        flush(current, out)
        return out
    }

    private fun isBoundary(text: String, at: Int): Boolean {
        var next = at + 1
        while (next < text.length && text[next] in "\"'”’)]") next++
        if (next < text.length && !text[next].isWhitespace()) return false // "3.5", "e.g.x", "U.S.A"
        if (text[at] != '.') return true
        // A word made only of initials or a known abbreviation does not end a sentence.
        var start = at
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        val word = text.substring(start, at).lowercase().trimStart('(', '"', '\'')
        if (word.isEmpty()) return true
        if (word in ABBREVIATIONS) return false
        // An initial ("J. Smith") is not a sentence end.
        if (word.length == 1 && text[at - 1].isUpperCase()) return false
        return true
    }

    private fun flush(current: StringBuilder, out: MutableList<String>) {
        val s = current.toString().trim()
        if (s.isNotEmpty()) out += s
        current.setLength(0)
    }

    /** Cuts one over-long sentence into pieces no longer than [maxChars]. */
    fun split(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            var cut = -1
            for (sep in listOf(", ", "; ", ": ", " — ", " - ")) {
                val idx = window.lastIndexOf(sep)
                if (idx > maxChars / 3) cut = maxOf(cut, idx + sep.length)
            }
            if (cut < 0) {
                val space = window.lastIndexOf(' ')
                cut = if (space > maxChars / 3) space + 1 else maxChars
            }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

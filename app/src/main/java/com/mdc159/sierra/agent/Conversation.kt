package com.mdc159.sierra.agent

/** Short rolling conversation with the driving-assistant system prompt. */
class Conversation(private val maxTurns: Int = 6) {

    private val turns = ArrayDeque<ChatMessage>()

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
    fun clear() = turns.clear()

    @Synchronized
    fun size(): Int = turns.size / 2

    companion object {
        const val SYSTEM_PROMPT =
            "You are Sierra, a driving assistant on the dashboard of the owner's GMC Sierra truck. " +
                "The driver is driving and will hear your answer read aloud. " +
                "Answer in one to three short sentences of plain spoken English: no lists, no markdown, " +
                "no URLs, no emoji. If a full answer needs more, give the key point and offer to continue. " +
                "Never encourage the driver to look at or type on a screen."

        /**
         * Shortens [text] to at most [max] characters for the car screen, preferring to cut
         * at a sentence end, then a word boundary, and marking the cut with an ellipsis.
         */
        fun truncateForScreen(text: String, max: Int = 300): String {
            val clean = text.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
            if (clean.length <= max) return clean
            val window = clean.substring(0, max - 1)
            val sentenceEnd = Regex("[.!?](\\s|$)").findAll(window).lastOrNull()?.range?.first
            if (sentenceEnd != null && sentenceEnd >= max / 2) return window.substring(0, sentenceEnd + 1) + " …"
            val space = window.lastIndexOf(' ')
            val cut = if (space >= max / 2) window.substring(0, space) else window
            return cut.trimEnd(',', ';', ':', ' ') + "…"
        }

        /** Strips markdown so text-to-speech doesn't read symbols aloud. */
        fun forSpeech(text: String, max: Int = 800): String =
            text.replace(Regex("[*_`#>|]+"), "")
                .replace(Regex("https?://\\S+"), "link")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(max)
    }
}

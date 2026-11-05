package com.mdc159.sierra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun roundTripsAndKeepsNewestFirst() {
        var entries = emptyList<Exchange>()
        repeat(History.MAX_ENTRIES + 5) { i ->
            entries = History.prepend(entries, Exchange(i.toLong(), "q$i", "a \"quoted\" $i"))
        }
        assertEquals(History.MAX_ENTRIES, entries.size)
        assertEquals("q${History.MAX_ENTRIES + 4}", entries.first().question)

        val decoded = History.decode(History.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun corruptFileYieldsEmptyHistory() {
        assertTrue(History.decode("not json").isEmpty())
        assertTrue(History.decode("""[{"q":"","a":""}, 5]""").isEmpty())
    }
}

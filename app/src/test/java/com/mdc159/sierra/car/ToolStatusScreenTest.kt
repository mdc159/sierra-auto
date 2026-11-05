package com.mdc159.sierra.car

import androidx.car.app.model.MessageTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status template shown while a tool runs must satisfy the library's builder checks. */
class ToolStatusScreenTest {

    @Test
    fun statusTemplateBuildsAsALoadingMessage() {
        val t = ToolStatusScreen.template("Finding fuel nearby…") as MessageTemplate
        assertEquals("Finding fuel nearby…", t.message.toString())
        assertEquals("Sierra", t.title.toString())
        assertTrue(t.isLoading)
        assertTrue(t.actions.isEmpty())
    }

    @Test
    fun veryLongStatusIsCutForTheScreen() {
        val t = ToolStatusScreen.template("x".repeat(500)) as MessageTemplate
        assertTrue(t.message.toString().length <= 80)
    }
}

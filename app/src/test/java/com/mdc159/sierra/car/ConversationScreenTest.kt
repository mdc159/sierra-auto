package com.mdc159.sierra.car

import androidx.car.app.model.MessageTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The conversation screen's templates must pass the Car App Library's builder checks. */
class ConversationScreenTest {

    @Test
    fun runningTemplateHasInterruptAndStop() {
        val t = ConversationScreen.runningTemplate(onInterrupt = {}, onStop = {}) as MessageTemplate
        assertEquals(2, t.actions.size)
        assertNotNull(t.headerAction)
    }

    @Test
    fun failedTemplateBuilds() {
        val t = ConversationScreen.failedTemplate("Set up full-duplex voice in Sierra on the phone.") as MessageTemplate
        assertEquals(0, t.actions.size)
    }
}

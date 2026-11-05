package com.mdc159.sierra.agent.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NavigateToToolTest {

    private class Recorder : Navigator {
        var name: String? = null
        var lat: Double? = null
        var lng: Double? = null
        override fun navigate(name: String, lat: Double?, lng: Double?) {
            this.name = name
            this.lat = lat
            this.lng = lng
        }
    }

    @Test
    fun navigatesToCoordinates() {
        val nav = Recorder()
        val out = JSONObject(NavigateToTool(nav).run(JSONObject().put("name", "Shell").put("lat", 38.51).put("lng", -121.49)))
        assertEquals("Shell", nav.name)
        assertEquals(38.51, nav.lat!!, 1e-9)
        assertEquals(-121.49, nav.lng!!, 1e-9)
        assertTrue(out.getBoolean("ok"))
        assertEquals("Shell", out.getString("navigating_to"))
    }

    @Test
    fun coordinatesAsStringsStillWork() {
        val nav = Recorder()
        NavigateToTool(nav).run(JSONObject().put("name", "Diner").put("lat", "38.5").put("lng", "-121.5"))
        assertEquals(38.5, nav.lat!!, 1e-9)
    }

    @Test
    fun nameOnlyLetsTheNavigationAppSearch() {
        val nav = Recorder()
        NavigateToTool(nav).run(JSONObject().put("name", "Home Depot"))
        assertEquals("Home Depot", nav.name)
        assertNull(nav.lat)
        assertNull(nav.lng)
        // Out-of-range coordinates are treated as absent.
        NavigateToTool(nav).run(JSONObject().put("name", "x").put("lat", 123.0).put("lng", 500.0))
        assertNull(nav.lat)
    }

    @Test
    fun missingNameIsAToolError() {
        try {
            NavigateToTool(Recorder()).run(JSONObject().put("lat", 1.0).put("lng", 2.0))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("Say where to go.", e.message)
        }
    }

    @Test
    fun navigatorFailureIsAToolError() {
        val tool = NavigateToTool { _, _, _ -> throw IllegalStateException("host gone") }
        try {
            tool.run(JSONObject().put("name", "x"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("Couldn't open navigation.", e.message)
        }
    }

    @Test
    fun statusNamesTheDestination() {
        val tool = NavigateToTool(Recorder())
        assertEquals("Navigating to Shell…", tool.status(JSONObject().put("name", "Shell")))
        assertEquals("Starting navigation…", tool.status(JSONObject()))
    }
}

package com.mdc159.sierra.agent.tools

import org.json.JSONObject

/** Starts navigation. TalkScreen wires this to the same hand-off the Places detail screen uses. */
fun interface Navigator {
    /** [lat] and [lng] are null when only a name is known; the navigation app then searches for it. */
    fun navigate(name: String, lat: Double?, lng: Double?)
}

/**
 * `navigate_to`: hands the destination to the default navigation app (Google Maps) with
 * the same intent the Places detail screen uses. The navigation app takes the screen.
 */
class NavigateToTool(private val navigator: Navigator) : Tool {

    override val name = "navigate_to"

    override val description =
        "Starts turn-by-turn navigation to a place in the car's navigation app. Pass the lat and lng " +
            "from find_places when you have them; with only a name, the navigation app searches for it."

    override val parameters: JSONObject = ToolSchema.obj(
        "name" to ToolSchema.string("The place name, shown on the destination."),
        "lat" to ToolSchema.number("Latitude from find_places, if known."),
        "lng" to ToolSchema.number("Longitude from find_places, if known."),
        required = listOf("name"),
    )

    override fun status(args: JSONObject): String {
        val name = args.optString("name").trim()
        return if (name.isEmpty()) "Starting navigation…" else "Navigating to $name…"
    }

    override fun run(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) throw ToolException("Say where to go.")
        val lat = args.optNumberOrNull("lat")
        val lng = args.optNumberOrNull("lng")
        val hasCoords = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0
        try {
            if (hasCoords) navigator.navigate(name, lat, lng) else navigator.navigate(name, null, null)
        } catch (e: Exception) {
            throw ToolException("Couldn't open navigation.", e)
        }
        return JSONObject()
            .put("ok", true)
            .put("navigating_to", name)
            .put("note", "Navigation has started and the map is on screen. Confirm in a few words.")
            .toString()
    }

    private fun JSONObject.optNumberOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) opt(key)?.toString()?.toDoubleOrNull() else v
    }
}

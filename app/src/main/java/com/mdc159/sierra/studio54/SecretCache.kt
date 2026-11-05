package com.mdc159.sierra.studio54

import org.json.JSONException
import org.json.JSONObject

/**
 * The last successful read of the vault, kept so the app keeps working when the phone is
 * off the private network. Persisted as JSON inside encrypted storage.
 */
class SecretCache(
    val projectId: String,
    val fetchedAt: Long,
    values: Map<String, String>,
) {
    val values: Map<String, String> = values.toSortedMap()

    fun ageMillis(now: Long): Long = (now - fetchedAt).coerceAtLeast(0)

    fun isFresh(now: Long, maxAgeMillis: Long): Boolean = ageMillis(now) <= maxAgeMillis

    fun toJson(): String = JSONObject()
        .put("projectId", projectId)
        .put("fetchedAt", fetchedAt)
        .put("values", JSONObject(values))
        .toString()

    /** Names and time only; never the values. */
    override fun toString(): String =
        "SecretCache(project=$projectId, fetchedAt=$fetchedAt, names=${values.keys})"

    companion object {
        /** Parses [json], or returns null if it is not a cache this build understands. */
        fun fromJson(json: String?): SecretCache? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val valuesObj = obj.getJSONObject("values")
                val values = LinkedHashMap<String, String>()
                valuesObj.keys().forEach { key -> values[key] = valuesObj.getString(key) }
                SecretCache(
                    projectId = obj.getString("projectId"),
                    fetchedAt = obj.getLong("fetchedAt"),
                    values = values,
                )
            } catch (e: JSONException) {
                null
            }
        }
    }
}

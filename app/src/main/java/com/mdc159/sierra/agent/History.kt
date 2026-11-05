package com.mdc159.sierra.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class Exchange(val timeMillis: Long, val question: String, val answer: String)

/**
 * Recent questions and answers, newest first, kept in a small JSON file in app-private
 * storage so the car can list them across sessions.
 */
object History {
    const val MAX_ENTRIES = 20
    private const val FILE = "history.json"
    private val lock = Any()

    fun list(context: Context): List<Exchange> = synchronized(lock) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun add(context: Context, question: String, answer: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val updated = prepend(list(context), Exchange(now, question, answer))
            File(context.filesDir, FILE).writeText(encode(updated))
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        File(context.filesDir, FILE).delete()
        Unit
    }

    fun prepend(entries: List<Exchange>, entry: Exchange): List<Exchange> =
        (listOf(entry) + entries).take(MAX_ENTRIES)

    fun encode(entries: List<Exchange>): String =
        JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("t", it.timeMillis).put("q", it.question).put("a", it.answer))
            }
        }.toString()

    fun decode(json: String): List<Exchange> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val q = o.optString("q")
            val a = o.optString("a")
            if (q.isBlank() && a.isBlank()) null else Exchange(o.optLong("t"), q, a)
        }
    } catch (e: JSONException) {
        emptyList()
    }
}

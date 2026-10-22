package com.example.financesmstracker.receiver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device record of what the app did with each incoming SMS, so a
 * message that never became a transaction can be explained afterwards.
 * Keeps only the newest [MAX_ENTRIES]; stored in private app preferences.
 */
object SmsHandlingLog {
    const val MAX_ENTRIES = 100
    private const val PREFS = "sms_handling_log"
    private const val KEY = "entries"
    private const val SNIPPET_LENGTH = 90

    data class Entry(val at: Long, val sender: String, val snippet: String, val outcome: String)

    fun snippetOf(body: String): String =
        body.replace(Regex("\\s+"), " ").trim().take(SNIPPET_LENGTH)

    /** Pure helper so the cap and ordering can be unit tested without Android. */
    fun appendCapped(existing: List<Entry>, entry: Entry): List<Entry> =
        (existing + entry).takeLast(MAX_ENTRIES)

    fun format(entries: List<Entry>): String {
        if (entries.isEmpty()) return "No SMS handled yet."
        val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)
        return entries.asReversed().joinToString("\n\n") {
            "${fmt.format(Date(it.at))}  ${it.sender}\n${it.snippet}\n→ ${it.outcome}"
        }
    }

    @Synchronized
    fun record(context: Context, sender: String, body: String, outcome: String) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val updated = appendCapped(
                readFrom(prefs.getString(KEY, null)),
                Entry(System.currentTimeMillis(), sender, snippetOf(body), outcome)
            )
            val array = JSONArray()
            updated.forEach {
                array.put(
                    JSONObject()
                        .put("at", it.at).put("sender", it.sender)
                        .put("snippet", it.snippet).put("outcome", it.outcome)
                )
            }
            prefs.edit().putString(KEY, array.toString()).apply()
        }
    }

    fun read(context: Context): List<Entry> = runCatching {
        readFrom(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        )
    }.getOrDefault(emptyList())

    private fun readFrom(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Entry(o.getLong("at"), o.getString("sender"), o.getString("snippet"), o.getString("outcome"))
        }
    }
}

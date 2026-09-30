package com.example.financesmstracker.integration

import android.content.Context
import org.json.JSONArray

/**
 * Durable FIFO queue for sync payloads. Failed network requests remain queued.
 * The queue is intentionally bounded to avoid unbounded device storage growth.
 */
class FinanceSyncQueue(context: Context) {
    private val prefs = context.getSharedPreferences("finance_sync_queue", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(payload: String) {
        val items = read().toMutableList()
        items.add(payload)
        while (items.size > MAX_ITEMS) items.removeAt(0)
        write(items)
    }

    @Synchronized
    fun peek(): String? = read().firstOrNull()

    @Synchronized
    fun removeHead() {
        val items = read().toMutableList()
        if (items.isNotEmpty()) {
            items.removeAt(0)
            write(items)
        }
    }

    @Synchronized
    fun size(): Int = read().size

    private fun read(): List<String> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getString(index) }
    }

    private fun write(items: List<String>) {
        prefs.edit().putString(KEY_ITEMS, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "pending_payloads"
        private const val MAX_ITEMS = 100
    }
}

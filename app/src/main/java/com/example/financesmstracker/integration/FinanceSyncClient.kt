package com.example.financesmstracker.integration

import com.example.financesmstracker.BuildConfig

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small dependency-free HTTPS client for the Oracle finance API.
 *
 * The endpoint and token are runtime configuration, so this app never points
 * at Render or another hosted service implicitly.
 */
class FinanceSyncClient(
    private val context: Context,
    private val baseUrl: String = SyncSettings(context).baseUrl(),
    private val token: String = SyncSettings(context).token()
) {
    fun send(jsonBody: String): Result<String> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        return runCatching {
            val endpoint = baseUrl.trimEnd('/') + "/api/v1/sync"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Sync-Token", token)
            }

            try {
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("Oracle sync HTTP $code: $body")
                body
            } finally {
                connection.disconnect()
            }
        }
    }
}

class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("finance_sync", Context.MODE_PRIVATE)

    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.FINANCE_SYNC_URL
    fun token(): String = prefs.getString(KEY_TOKEN, null) ?: BuildConfig.FINANCE_SYNC_TOKEN

    fun save(baseUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
            .putString(KEY_TOKEN, token)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "oracle_base_url"
        private const val KEY_TOKEN = "oracle_sync_token"
    }
}

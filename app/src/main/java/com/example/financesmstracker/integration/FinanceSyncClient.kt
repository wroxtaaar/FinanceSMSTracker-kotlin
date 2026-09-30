package com.example.financesmstracker.integration

import com.example.financesmstracker.BuildConfig

import android.content.Context
import org.json.JSONObject
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
    fun fetchAccounts(): Result<List<OracleAccount>> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        return get("/api/v1/accounts").map { body ->
            val accounts = JSONObject(body).getJSONArray("accounts")
            buildList(accounts.length()) {
                for (index in 0 until accounts.length()) {
                    val item = accounts.getJSONObject(index)
                    add(
                        OracleAccount(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            currency = item.optString("currency", "INR"),
                            accountType = item.optString("account_type"),
                            bank = item.optString("bank").takeIf { it.isNotBlank() },
                            last4 = item.optString("last4").takeIf { it.isNotBlank() },
                            openingBalanceMinor = item.optLong("opening_balance_minor"),
                            balanceMinor = item.getLong("balance_minor")
                        )
                    )
                }
            }
        }
    }

    fun fetchSummary(): Result<OracleLedgerSummary> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        return get("/api/v1/summary").map { body ->
            val json = JSONObject(body)
            OracleLedgerSummary(
                currency = json.optString("currency", "INR"),
                bankCashMinor = json.getLong("bankCashMinor"),
                splitwiseReceivableMinor = json.getLong("splitwiseReceivableMinor"),
                creditCardOutstandingMinor = json.getLong("creditCardOutstandingMinor"),
                trueAvailableMinor = json.getLong("trueAvailableMinor")
            )
        }
    }

    private fun get(path: String): Result<String> {
        return runCatching {
            val endpoint = baseUrl.trimEnd('/') + path
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Sync-Token", token)
            }

            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("Oracle request HTTP $code: $body")
                body
            } finally {
                connection.disconnect()
            }
        }
    }

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

data class OracleAccount(
    val id: String,
    val name: String,
    val currency: String,
    val accountType: String,
    val bank: String?,
    val last4: String?,
    val openingBalanceMinor: Long,
    val balanceMinor: Long
)

data class OracleLedgerSummary(
    val currency: String,
    val bankCashMinor: Long,
    val splitwiseReceivableMinor: Long,
    val creditCardOutstandingMinor: Long,
    val trueAvailableMinor: Long
)

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

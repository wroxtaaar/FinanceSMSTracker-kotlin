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

    fun updateAccountBalance(account: OracleAccount, balanceMinor: Long): Result<String> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        val json = JSONObject().apply {
            put("id", account.id)
            put("name", account.name)
            put("currency", account.currency)
            put("accountType", account.accountType)
            account.bank?.let { put("bank", it) }
            account.last4?.let { put("last4", it) }
            put("balanceMinor", balanceMinor)
        }

        val encodedId = java.net.URLEncoder.encode(account.id, "UTF-8")
        return put("/api/v1/accounts/" + encodedId + "/balance", json.toString())
    }

    fun fetchManualSplitwiseTotal(): Result<Long> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        return get("/api/v1/splitwise/manual-total").map { body ->
            JSONObject(body).getLong("amountMinor")
        }
    }

    fun updateManualSplitwiseTotal(amountMinor: Long): Result<String> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        val json = JSONObject().apply {
            put("amountMinor", amountMinor)
            put("currency", "INR")
        }
        return put("/api/v1/splitwise/manual-total", json.toString())
    }

    fun triggerGmailSync(): Result<GmailSyncResult> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        // Manual Gmail checks use a bounded 30-day historical window so emails
        // missed during an earlier UIDVALIDITY/incremental-scan period can be
        // recovered, while the scheduled worker continues to use incremental mode.
        return post(
            "/api/v1/gmail/sync?query=newer_than%3A30d&historical=true",
            "{}"
        ).map { body ->
            val json = JSONObject(body)
            GmailSyncResult(
                messagesScanned = json.optInt("messagesScanned", 0),
                alreadyProcessed = json.optInt("alreadyProcessed", 0),
                parsedTransactions = json.optInt("parsedTransactions", 0),
                axisCredits = json.optInt("axisCredits", 0),
                duplicateTransactions = json.optInt("duplicateTransactions", 0),
                reviewCount = json.optInt("reviewCount", 0),
                ignoredCount = json.optInt("ignoredCount", 0),
                createdEvidence = json.optInt("createdEvidence", 0),
                repairedTransactions = json.optInt("repairedTransactions", 0)
            )
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

    private fun post(path: String, jsonBody: String): Result<String> {
        return runCatching {
            val endpoint = baseUrl.trimEnd('/') + path
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                // Gmail sync may need to fetch several new messages from IMAP.
                // Keep enough time for that work instead of failing at 30s.
                readTimeout = 120_000
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
                if (code !in 200..299) throw IOException("Oracle request HTTP $code: $body")
                body
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun put(path: String, jsonBody: String): Result<String> {
        return runCatching {
            val endpoint = baseUrl.trimEnd('/') + path
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
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
                if (code !in 200..299) throw IOException("Oracle request HTTP $code: $body")
                body
            } finally {
                connection.disconnect()
            }
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

        return post("/api/v1/sync", jsonBody)
    }
}

data class GmailSyncResult(
    val messagesScanned: Int,
    val alreadyProcessed: Int,
    val parsedTransactions: Int,
    val axisCredits: Int,
    val duplicateTransactions: Int,
    val reviewCount: Int,
    val ignoredCount: Int,
    val createdEvidence: Int,
    val repairedTransactions: Int
)

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

    fun hasSplitwiseGroups(): Boolean =
        prefs.contains(KEY_SPLITWISE_GROUP_1) || prefs.contains(KEY_SPLITWISE_GROUP_2)

    fun splitwiseGroup1Minor(): Long = prefs.getLong(KEY_SPLITWISE_GROUP_1, 0L)

    fun splitwiseGroup2Minor(): Long = prefs.getLong(KEY_SPLITWISE_GROUP_2, 0L)

    fun saveSplitwiseGroups(group1Minor: Long, group2Minor: Long) {
        prefs.edit()
            .putLong(KEY_SPLITWISE_GROUP_1, group1Minor)
            .putLong(KEY_SPLITWISE_GROUP_2, group2Minor)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "oracle_base_url"
        private const val KEY_TOKEN = "oracle_sync_token"
        private const val KEY_SPLITWISE_GROUP_1 = "splitwise_group_1_minor"
        private const val KEY_SPLITWISE_GROUP_2 = "splitwise_group_2_minor"
    }
}

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
                            balanceMinor = item.getLong("balance_minor"),
                            billBalanceMinor = item.optLong(
                                "bill_balance_minor",
                                if (item.optString("account_type") == "CREDIT_CARD") item.getLong("balance_minor") else 0L
                            )
                        )
                    )
                }
            }
        }
    }

    fun updateAccountBalance(
        account: OracleAccount,
        balanceMinor: Long,
        billBalanceMinor: Long? = null
    ): Result<String> {
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
            if (account.accountType == "CREDIT_CARD" && billBalanceMinor != null) {
                put("billBalanceMinor", billBalanceMinor)
            }
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
                repairedTransactions = json.optInt("repairedTransactions", 0),
                messageLog = json.optJSONArray("messageLog")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.optString("sender") + " | " + o.optString("subject") +
                            "\n→ " + o.optString("outcome")
                    }
                } ?: emptyList(),
                searchSummary = json.optJSONObject("gmailDiagnostics")?.let { d ->
                    val counts = d.optJSONObject("senderSearchCounts")
                    val hits = counts?.keys()?.asSequence()
                        ?.filter { counts.optInt(it, 0) > 0 }
                        ?.joinToString(", ") { it + "=" + counts.optInt(it) }
                        .orEmpty()
                    "folder=" + d.optString("folder") + ", candidates=" +
                        d.optInt("candidateUidCount") + ", senders with mail: " +
                        hits.ifEmpty { "none" }
                }.orEmpty()
            )
        }
    }

    fun fetchGmailTransactions(): Result<List<OracleTransaction>> =
        fetchOracleTransactions(onlyGmail = true)

    /** Every active transaction the server holds, for "Restore history from server". */
    fun fetchAllOracleTransactions(): Result<List<OracleTransaction>> =
        fetchOracleTransactions(onlyGmail = false)

    private fun fetchOracleTransactions(onlyGmail: Boolean): Result<List<OracleTransaction>> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))

        return get("/api/v1/transactions?limit=1000").map { body ->
            val items = JSONObject(body).getJSONArray("transactions")
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    if (onlyGmail && !id.startsWith("gmail:")) continue

                    add(
                        OracleTransaction(
                            id = id,
                            amountMinor = item.getLong("amount_minor"),
                            currency = item.optString("currency", "INR"),
                            transactionType = item.optString("type", "UNKNOWN"),
                            paymentMethod = item.optString("payment_method", "UNKNOWN"),
                            accountType = item.optString("account_type", "UNKNOWN"),
                            bank = cleanRemoteString(item.optString("bank")),
                            merchantOrPayee = cleanRemoteString(item.optString("merchant_or_payee")),
                            accountLast4 = cleanRemoteString(item.optString("account_last4")),
                            reference = cleanRemoteString(item.optString("reference")),
                            timestamp = item.getLong("timestamp"),
                            category = item.optString("category", "OTHER"),
                            confidence = item.optDouble("confidence", 0.0).toFloat()
                        )
                    )
                }
            }
        }
    }

    data class ForeignAmountInfo(
        val foreign: Boolean,
        val inrMinor: Long = 0L,
        val origAmountMinor: Long = 0L,
        val origCurrency: String = "",
        val exact: Boolean = false
    )

    /** Foreign-spend details for a row, as Oracle knows it (foreign=false for rupee rows). */
    fun fetchForeignAmountInfo(oracleId: String): Result<ForeignAmountInfo> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        return get("/api/v1/transactions/" + android.net.Uri.encode(oracleId) + "/fx").map { body ->
            val json = JSONObject(body)
            if (!json.optBoolean("foreign", false)) {
                ForeignAmountInfo(foreign = false)
            } else {
                ForeignAmountInfo(
                    foreign = true,
                    inrMinor = json.getLong("inrMinor"),
                    origAmountMinor = json.getLong("origAmountMinor"),
                    origCurrency = json.optString("origCurrency", ""),
                    exact = json.optBoolean("exact", false)
                )
            }
        }
    }

    /** Every active foreign spend Oracle knows, keyed by Oracle transaction id. */
    fun fetchForeignAmounts(): Result<Map<String, ForeignAmountInfo>> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        return get("/api/v1/foreign-amounts").map { body ->
            val items = JSONObject(body).optJSONObject("items") ?: JSONObject()
            items.keys().asSequence().associateWith { key ->
                val o = items.getJSONObject(key)
                ForeignAmountInfo(
                    foreign = true,
                    inrMinor = o.getLong("inrMinor"),
                    origAmountMinor = o.getLong("origAmountMinor"),
                    origCurrency = o.optString("origCurrency", ""),
                    exact = o.optBoolean("exact", false)
                )
            }
        }
    }

    fun setForeignInrAmount(oracleId: String, amountMinor: Long): Result<String> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        val json = JSONObject().apply { put("amountMinor", amountMinor) }
        return put("/api/v1/transactions/" + android.net.Uri.encode(oracleId) + "/inr-amount", json.toString())
    }

    data class SalaryShare(val amountMinor: Long, val shareMinor: Long)

    /** How much of a salary credit counts toward Splitwise (fails if Oracle has no such salary row). */
    fun fetchSalaryShare(oracleId: String): Result<SalaryShare> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        return get("/api/v1/transactions/" + android.net.Uri.encode(oracleId) + "/splitwise-share").map { body ->
            val json = JSONObject(body)
            SalaryShare(json.getLong("amountMinor"), json.getLong("shareMinor"))
        }
    }

    fun setSalaryShare(oracleId: String, shareMinor: Long): Result<String> {
        if (baseUrl.isBlank()) return Result.failure(IllegalStateException("Oracle URL is not configured"))
        if (token.isBlank()) return Result.failure(IllegalStateException("Oracle sync token is not configured"))
        val json = JSONObject().apply { put("shareMinor", shareMinor) }
        return put("/api/v1/transactions/" + android.net.Uri.encode(oracleId) + "/splitwise-share", json.toString())
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

    private fun cleanRemoteString(value: String?): String? {
        val normalized = value?.trim()
        return normalized
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !it.equals("null", ignoreCase = true) }
            ?.takeIf { !it.equals("none", ignoreCase = true) }
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
    val repairedTransactions: Int,
    /** One line per scanned e-mail: "sender | subject → what the scan did". */
    val messageLog: List<String> = emptyList(),
    /** Compact IMAP search summary (folder, per-sender hit counts). */
    val searchSummary: String = ""
)

data class OracleTransaction(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    val transactionType: String,
    val paymentMethod: String,
    val accountType: String,
    val bank: String?,
    val merchantOrPayee: String?,
    val accountLast4: String?,
    val reference: String?,
    val timestamp: Long,
    val category: String?,
    val confidence: Float
)

data class OracleAccount(
    val id: String,
    val name: String,
    val currency: String,
    val accountType: String,
    val bank: String?,
    val last4: String?,
    val openingBalanceMinor: Long,
    val balanceMinor: Long,
    val billBalanceMinor: Long = 0L
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

    fun splitwiseGroup3Minor(): Long = prefs.getLong(KEY_SPLITWISE_GROUP_3, 0L)

    fun saveSplitwiseGroups(group1Minor: Long, group2Minor: Long, group3Minor: Long) {
        prefs.edit()
            .putLong(KEY_SPLITWISE_GROUP_1, group1Minor)
            .putLong(KEY_SPLITWISE_GROUP_2, group2Minor)
            .putLong(KEY_SPLITWISE_GROUP_3, group3Minor)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "oracle_base_url"
        private const val KEY_TOKEN = "oracle_sync_token"
        private const val KEY_SPLITWISE_GROUP_1 = "splitwise_group_1_minor"
        private const val KEY_SPLITWISE_GROUP_2 = "splitwise_group_2_minor"
        private const val KEY_SPLITWISE_GROUP_3 = "splitwise_group_3_minor"
    }
}

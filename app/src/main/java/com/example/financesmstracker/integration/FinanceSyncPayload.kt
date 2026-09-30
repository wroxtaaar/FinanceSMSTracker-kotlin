package com.example.financesmstracker.integration

import org.json.JSONArray
import org.json.JSONObject

object FinanceSyncPayload {
    fun build(
        transactions: List<SyncTransaction>,
        evidence: List<SyncEvidence>
    ): String {
        val root = JSONObject()
            .put("version", FinanceSyncContract.VERSION)
            .put("transactions", JSONArray().also { array ->
                transactions.forEach { t ->
                    array.put(JSONObject()
                        .put("id", t.localTransactionId.toString())
                        .put("amountMinor", t.amountMinor)
                        .put("currency", t.currency)
                        .put("type", t.transactionType.name)
                        .put("paymentMethod", t.paymentMethod.name)
                        .put("accountType", t.accountType.name)
                        .put("bank", t.bank)
                        .put("merchantOrPayee", t.merchantName ?: t.payeeId)
                        .put("accountLast4", t.accountLastFour)
                        .put("reference", t.reference)
                        .put("timestamp", t.timestamp)
                        .put("category", t.category ?: "OTHER")
                        .put("confidence", t.parserConfidence))
                }
            })
            .put("evidence", JSONArray().also { array ->
                evidence.forEach { e ->
                    array.put(JSONObject()
                        .put("id", e.localEvidenceId.toString())
                        .put("sourceType", e.sourceType.name)
                        .put("sourceId", e.sourceKey)
                        .put("status", e.status.name)
                        .put("observedAt", e.receivedAt)
                        .put("transactionId", e.localTransactionId?.toString())
                        .put("matchedTransactionId", e.localTransactionId?.toString()))
                }
            })
        return root.toString()
    }
}

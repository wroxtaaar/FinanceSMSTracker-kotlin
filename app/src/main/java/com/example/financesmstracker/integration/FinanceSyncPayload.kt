package com.example.financesmstracker.integration

/**
 * Builds the versioned sync payload without depending on Android's org.json
 * implementation.
 *
 * Keeping serialization pure Kotlin makes the contract deterministic in both
 * Android runtime and JVM unit tests.
 */
object FinanceSyncPayload {
    fun build(
        transactions: List<SyncTransaction>,
        evidence: List<SyncEvidence>,
        voidedTransactionIds: List<Long> = emptyList()
    ): String {
        val out = StringBuilder(256)
        out.append("{")
        out.append("\"version\":").append(FinanceSyncContract.VERSION)
        out.append(",\"transactions\":[")
        transactions.forEachIndexed { index, t ->
            if (index > 0) out.append(",")
            out.append("{")
            out.append("\"id\":").append(jsonString(t.localTransactionId.toString()))
            out.append(",\"amountMinor\":").append(t.amountMinor)
            out.append(",\"currency\":").append(jsonString(t.currency))
            out.append(",\"type\":").append(jsonString(t.transactionType.name))
            out.append(",\"paymentMethod\":").append(jsonString(t.paymentMethod.name))
            out.append(",\"accountType\":").append(jsonString(t.accountType.name))
            appendNullableString(out, "bank", t.bank)
            appendNullableString(out, "merchantOrPayee", t.merchantName ?: t.payeeId)
            appendNullableString(out, "accountLast4", t.accountLastFour)
            appendNullableString(out, "reference", t.reference)
            out.append(",\"timestamp\":").append(t.timestamp)
            out.append(",\"category\":").append(jsonString(t.category ?: "OTHER"))
            require(t.parserConfidence.isFinite()) { "parserConfidence must be finite" }
            out.append(",\"confidence\":").append(t.parserConfidence)
            out.append("}")
        }
        out.append("]")

        out.append(",\"evidence\":[")
        evidence.forEachIndexed { index, e ->
            if (index > 0) out.append(",")
            out.append("{")
            out.append("\"id\":").append(jsonString(e.localEvidenceId.toString()))
            out.append(",\"sourceType\":").append(jsonString(e.sourceType.name))
            out.append(",\"sourceId\":").append(jsonString(e.sourceKey))
            out.append(",\"status\":").append(jsonString(e.status.name))
            out.append(",\"observedAt\":").append(e.receivedAt)
            appendNullableString(out, "transactionId", e.localTransactionId?.toString())
            appendNullableString(out, "matchedTransactionId", e.localTransactionId?.toString())
            out.append(",\"amountMinor\":").append(e.amountMinor)
            appendNullableString(out, "currency", e.currency)
            appendNullableString(out, "direction", e.direction)
            appendNullableString(out, "bankProvider", e.bankProvider)
            appendNullableString(out, "accountLast4", e.accountLastFour)
            appendNullableString(out, "reference", e.reference)
            appendNullableString(out, "contentHash", e.contentHash)
            require(e.confidence.isFinite()) { "evidence confidence must be finite" }
            out.append(",\"confidence\":").append(e.confidence)
            out.append("}")
        }
        out.append("]")

        out.append(",\"voidedTransactionIds\":[")
        voidedTransactionIds.forEachIndexed { index, id ->
            if (index > 0) out.append(",")
            out.append(jsonString(id.toString()))
        }
        out.append("]")
        out.append("}")
        return out.toString()
    }

    private fun appendNullableString(
        out: StringBuilder,
        name: String,
        value: String?
    ) {
        if (value != null) {
            out.append(",\"").append(name).append("\":")
                .append(jsonString(value))
        }
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('\"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '\"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                            .append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('\"')
        return out.toString()
    }
}

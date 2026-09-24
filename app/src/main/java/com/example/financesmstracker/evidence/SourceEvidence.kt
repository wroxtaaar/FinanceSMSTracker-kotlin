package com.example.financesmstracker.evidence

enum class SourceType {
    SMS,
    TRUECALLER
}

enum class EvidenceStatus {
    UNMATCHED,
    MATCHED,
    AMBIGUOUS
}

data class SourceEvidence(
    val id: Long = 0L,
    val sourceType: SourceType,
    val sourceKey: String,
    val receivedAt: Long,
    val transactionId: Long? = null,
    val amountPaise: Long,
    val direction: String,
    val bankProvider: String? = null,
    val accountLastFour: String? = null,
    val reference: String? = null,
    val contentHash: String,
    val confidence: Float = 1.0f,
    val status: EvidenceStatus = EvidenceStatus.UNMATCHED
)

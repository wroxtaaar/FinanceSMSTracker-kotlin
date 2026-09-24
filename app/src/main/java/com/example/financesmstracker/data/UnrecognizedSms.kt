package com.example.financesmstracker.data

enum class ReviewStatus {
    REVIEW,
    DISMISSED,
    RESOLVED
}

data class UnrecognizedSms(
    val id: Long = 0L,
    val sender: String,
    val receivedAt: Long,
    val contentHash: String,
    val reason: String,
    val status: ReviewStatus = ReviewStatus.REVIEW
)

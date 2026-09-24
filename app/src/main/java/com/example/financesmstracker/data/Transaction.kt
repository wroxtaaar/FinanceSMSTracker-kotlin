package com.example.financesmstracker.data

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

enum class PayeeIdentifierType {
    UPI_VPA,
    PAYEE_NAME,
    NONE
}

data class Transaction(
    val id: Long = 0L,
    val amountPaise: Long,
    val transactionType: TransactionType,
    val paymentMethod: PaymentMethod,
    val accountType: AccountType,
    val bank: String?,
    val merchantName: String?,
    val payeeId: String?,
    val accountLastFour: String?,
    val refNumber: String?,
    val timestamp: Long,
    val smsHash: String,
    val category: String?,
    val parserConfidence: Float
) {
    val stableIdentifier: String?
        get() = payeeId?.takeIf { !it.isBlank() } ?: merchantName?.takeIf { !it.isBlank() }

    val identifierType: PayeeIdentifierType
        get() = when {
            !payeeId.isNullOrBlank() -> PayeeIdentifierType.UPI_VPA
            !merchantName.isNullOrBlank() -> PayeeIdentifierType.PAYEE_NAME
            else -> PayeeIdentifierType.NONE
        }
}

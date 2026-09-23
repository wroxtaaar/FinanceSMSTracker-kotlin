package com.example.financesmstracker.parser

data class ParserResult(
    val isTransaction: Boolean,
    val amountPaise: Long = 0L,
    val transactionType: TransactionType = TransactionType.UNKNOWN,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val accountType: AccountType = AccountType.UNKNOWN,
    val bank: String? = null,
    val merchantName: String? = null,
    val payeeId: String? = null,
    val accountLastFour: String? = null,
    val refNumber: String? = null,
    val confidence: Float = 0.0f
)

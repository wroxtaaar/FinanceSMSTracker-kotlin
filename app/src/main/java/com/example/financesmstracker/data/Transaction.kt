package com.example.financesmstracker.data

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

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
)

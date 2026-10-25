package com.example.financesmstracker.data

import android.database.Cursor
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

internal fun normalizedBank(value: String?): String? =
    value?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
        ?.uppercase()
        ?.removeSuffix(" BANK")
        ?.removeSuffix(" LTD")
        ?.removeSuffix(" LIMITED")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun normalizeBank(bank: String?): String? {
    if (bank.isNullOrBlank()) return null
    val upper = bank.trim().uppercase()
    return when {
        upper.contains("AXIS") -> "AXIS"
        upper.contains("HDFC") -> "HDFC"
        upper.contains("ICICI") -> "ICICI"
        upper.contains("SBI") -> "SBI"
        upper.contains("KOTAK") -> "KOTAK"
        upper.contains("PAYTM") -> "PAYTM"
        else -> upper.replace(" BANK", "").trim()
    }
}

internal fun cursorToTransaction(cursor: Cursor): Transaction {
    return Transaction(
        id = cursor.getLong(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_ID
            )
        ),
        amountPaise = cursor.getLong(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE
            )
        ),
        currency = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_CURRENCY
            )
        ),
        transactionType = TransactionType.valueOf(
            cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE
                )
            )
        ),
        paymentMethod = PaymentMethod.valueOf(
            cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD
                )
            )
        ),
        accountType = AccountType.valueOf(
            cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE
                )
            )
        ),
        bank = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_BANK
            )
        ),
        merchantName = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_MERCHANT_NAME
            )
        ),
        payeeId = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_PAYEE_ID
            )
        ),
        accountLastFour = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR
            )
        ),
        refNumber = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_REF_NUMBER
            )
        ),
        timestamp = cursor.getLong(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_TIMESTAMP
            )
        ),
        smsHash = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_SMS_HASH
            )
        ),
        category = cursor.getString(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_CATEGORY
            )
        ),
        parserConfidence = cursor.getFloat(
            cursor.getColumnIndexOrThrow(
                FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE
            )
        )
    )
}

internal fun cursorToEvidence(cursor: Cursor): SourceEvidence {
    return SourceEvidence(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_ID)),
        sourceType = SourceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_TYPE))),
        sourceKey = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_KEY)),
        receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT)),
        transactionId = if (cursor.isNull(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID))) null else cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID)),
        amountPaise = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE)),
        currency = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CURRENCY)),
        direction = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_DIRECTION)),
        bankProvider = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_BANK_PROVIDER)),
        accountLastFour = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR)),
        reference = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_REFERENCE)),
        contentHash = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONTENT_HASH)),
        confidence = cursor.getFloat(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONFIDENCE)),
        status = EvidenceStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS)))
    )
}

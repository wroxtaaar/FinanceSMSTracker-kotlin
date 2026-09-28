package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction

data class InternalTransferCandidate(
    val debit: Transaction,
    val credit: Transaction,
    val timeDifferenceMillis: Long
)

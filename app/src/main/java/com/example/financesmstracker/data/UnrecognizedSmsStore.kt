package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log

internal class UnrecognizedSmsStore(
    private val dbHelper: FinanceDatabaseHelper
) {

    fun insertUnrecognizedSms(unrecognized: UnrecognizedSms): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER, unrecognized.sender)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT, unrecognized.receivedAt)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH, unrecognized.contentHash)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON, unrecognized.reason)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS, unrecognized.status.name)
        }

        val rowId = db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        if (rowId != -1L) {
            Log.d("FinanceSource", "UNRECOGNIZED_SMS_RECORDED -> ID: $rowId, sender: ${unrecognized.sender}, reason: ${unrecognized.reason}")
        }
        return rowId
    }

    fun getUnresolvedUnrecognizedSms(): List<UnrecognizedSms> {
        val list = mutableListOf<UnrecognizedSms>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS} = ?",
            arrayOf(ReviewStatus.REVIEW.name),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    UnrecognizedSms(
                        id = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID)),
                        sender = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER)),
                        receivedAt = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT)),
                        contentHash = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH)),
                        reason = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON)),
                        status = ReviewStatus.valueOf(it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS)))
                    )
                )
            }
        }
        return list
    }

    fun getUnresolvedUnrecognizedSmsCount(): Int {
        val db = dbHelper.readableDatabase
        return db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            arrayOf("COUNT(*)"),
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS} = ?",
            arrayOf(ReviewStatus.REVIEW.name),
            null,
            null,
            null
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun updateUnrecognizedSmsStatus(id: Long, status: ReviewStatus): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS, status.name)
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            values,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun getUnrecognizedSmsByHash(contentHash: String): UnrecognizedSms? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH} = ?",
            arrayOf(contentHash),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return UnrecognizedSms(
                    id = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID)),
                    sender = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER)),
                    receivedAt = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT)),
                    contentHash = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH)),
                    reason = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON)),
                    status = ReviewStatus.valueOf(it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS)))
                )
            }
        }
        return null
    }
}

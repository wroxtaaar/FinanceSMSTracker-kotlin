package com.example.financesmstracker.integration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * How a phone row is identified on the Oracle server.
 *
 * The phone's own row numbers restart at 1 after a reinstall, so sending them
 * as-is would make new rows overwrite the history of an earlier install. Rows
 * that existed when this scheme was introduced keep their bare numeric id (so
 * edits and deletes of already-synced rows still find them); every row created
 * afterwards is sent as "<install prefix>-<row number>", which is unique per
 * install.
 */
object OracleIds {
    private const val PREFS = "oracle_sync_ids"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_LEGACY_MAX_TX = "legacy_max_tx_id"
    private const val KEY_LEGACY_MAX_EVIDENCE = "legacy_max_evidence_id"

    data class Scheme(
        val prefix: String,
        val legacyMaxTransactionId: Long,
        val legacyMaxEvidenceId: Long
    ) {
        fun transactionId(localId: Long): String =
            if (localId <= legacyMaxTransactionId) localId.toString() else "$prefix-$localId"

        fun evidenceId(localId: Long): String =
            if (localId <= legacyMaxEvidenceId) localId.toString() else "$prefix-e$localId"
    }

    /** Every id stays a bare number: the behaviour before this scheme existed. */
    val BARE = Scheme("", Long.MAX_VALUE, Long.MAX_VALUE)

    /** Pure: a fresh install prefix. */
    fun newPrefix(): String = UUID.randomUUID().toString().replace("-", "").take(6)

    /**
     * Record the boundary once, when the database is first opened by a build
     * that has this scheme. A brand-new database has no rows, so every row it
     * ever holds gets a prefixed id.
     */
    fun initialize(context: Context, db: SQLiteDatabase) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PREFIX)) return

        fun maxId(table: String): Long =
            db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM $table", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }

        val maxTx = runCatching { maxId("transactions") }.getOrDefault(0L)
        val maxEvidence = runCatching { maxId("source_evidence") }.getOrDefault(0L)
        prefs.edit()
            .putLong(KEY_LEGACY_MAX_TX, maxTx)
            .putLong(KEY_LEGACY_MAX_EVIDENCE, maxEvidence)
            .putString(KEY_PREFIX, newPrefix())
            .apply()
    }

    /** The saved scheme, or [BARE] if the database has not been opened yet. */
    fun scheme(context: Context): Scheme {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefs.getString(KEY_PREFIX, null) ?: return BARE
        return Scheme(
            prefix,
            prefs.getLong(KEY_LEGACY_MAX_TX, 0L),
            prefs.getLong(KEY_LEGACY_MAX_EVIDENCE, 0L)
        )
    }
}

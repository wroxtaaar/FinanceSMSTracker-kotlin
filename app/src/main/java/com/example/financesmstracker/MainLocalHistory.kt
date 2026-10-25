package com.example.financesmstracker

import android.app.AlertDialog
import android.widget.Toast

internal fun MainActivity.showClearLocalHistoryDialog() {
    AlertDialog.Builder(this)
        .setTitle("Clear Local History?")
        .setMessage(
            "This removes transactions, local evidence, and unrecognized SMS records from this phone. " +
                "Pending local sync payloads will also be discarded. Oracle data, account settings, " +
                "Gmail processing history, and category memory are not changed."
        )
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Clear") { _, _ ->
            clearLocalHistory()
        }
        .show()
}

internal fun MainActivity.clearLocalHistory() {
    buttonClearLocalHistory.isEnabled = false
    oracleExecutor.execute {
        // Capture the Oracle Gmail IDs that existed at the moment of the
        // clear. Timestamp cutoffs are useful, but an imported email can
        // carry a bad/future event timestamp. Exact tombstones make the
        // clear operation durable across app restarts and re-syncs.
        // Gmail is disabled during SMS/notification testing. Do not contact
        // any Gmail endpoint even when clearing local history.
        val remoteIds = emptySet<String>()

        val existingLocalHashes = repository.getAllTransactions()
            .mapNotNull { it.smsHash.takeIf(String::isNotBlank) }
            .toSet()

        val previousRemoteIds =
            localHistoryPrefs.getStringSet(MainActivity.KEY_LOCAL_HISTORY_CLEARED_ORACLE_IDS, emptySet())
                ?: emptySet()
        val previousLocalHashes =
            localHistoryPrefs.getStringSet(MainActivity.KEY_LOCAL_HISTORY_CLEARED_SMS_HASHES, emptySet())
                ?: emptySet()

        val clearedAt = System.currentTimeMillis()
        localHistoryPrefs.edit()
            .putLong(MainActivity.KEY_LOCAL_HISTORY_CLEARED_AT, clearedAt)
            .putStringSet(
                MainActivity.KEY_LOCAL_HISTORY_CLEARED_ORACLE_IDS,
                previousRemoteIds + remoteIds
            )
            .putStringSet(
                MainActivity.KEY_LOCAL_HISTORY_CLEARED_SMS_HASHES,
                previousLocalHashes + existingLocalHashes
            )
            .apply()

        val cleared = repository.clearLocalHistory()

        val queue = com.example.financesmstracker.integration.FinanceSyncQueue(this@clearLocalHistory)
        val pending = queue.size()
        queue.clear()

        runOnUiThread {
            loadTransactions()
            updateReviewCount()
            buttonClearLocalHistory.isEnabled = true
            Toast.makeText(
                this@clearLocalHistory,
                "Cleared " + cleared.transactions +
                    " transactions and " + pending + " pending sync payloads",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

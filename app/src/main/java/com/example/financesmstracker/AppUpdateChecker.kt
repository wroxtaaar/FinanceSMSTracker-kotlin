package com.example.financesmstracker

import android.util.Log
import com.google.firebase.appdistribution.FirebaseAppDistribution

/**
 * Asks Firebase App Distribution whether a newer build is available and, if so,
 * shows its built-in dialog to download and install it.
 *
 * Safe to call anywhere: it does nothing in debug builds (API-only SDK) or when
 * Firebase is not configured (no google-services.json), and it never crashes
 * the app.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private var checkedThisProcess = false

    fun check() {
        // Once per app launch, so the dialog is not re-shown on every resume.
        if (checkedThisProcess) return
        checkedThisProcess = true

        try {
            FirebaseAppDistribution.getInstance()
                .updateIfNewReleaseAvailable()
                .addOnFailureListener { error ->
                    Log.w(TAG, "Update check failed: ${error.message}")
                }
        } catch (error: Throwable) {
            Log.w(TAG, "Update check skipped: ${error.message}")
        }
    }
}

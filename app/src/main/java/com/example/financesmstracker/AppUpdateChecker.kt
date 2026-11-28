package com.example.financesmstracker

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.financesmstracker.integration.SyncSettings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** True when the published build is newer than the installed one. */
internal fun isNewerBuild(installed: Long, latest: Long): Boolean = latest > installed

data class AppRelease(val versionCode: Long, val versionName: String, val sha256: String)

/**
 * Self-update from the private Oracle server (never a public download): CI uploads the signed APK to the
 * VPS, this asks /api/v1/app/version, downloads /api/v1/app/apk with the sync token, checks the SHA-256 and
 * hands it to Android's PackageInstaller. Once this app has installed an update itself, Android 12+ lets
 * later updates install without a confirmation tap.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val ACTION = "com.example.financesmstracker.INSTALL_STATUS"
    private var lastCheckAt = 0L
    private var receiverRegistered = false

    /** The newer build found on the server, or null. The home screen shows an Update button while this is set. */
    @Volatile var available: AppRelease? = null
        private set
    var onAvailableChanged: (() -> Unit)? = null

    /** Looks for a newer build in the background (at most once a minute). Never installs by itself. Runs on every return to the app and every couple of minutes while it is open. */
    fun check(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < 60_000L) return
        lastCheckAt = now
        look(activity, manual = false)
    }

    /** From Settings: same look, but tells the user what happened. */
    @Volatile private var looking = false

    fun checkNow(activity: Activity) {
        if (looking) return
        looking = true
        look(activity, manual = true)
    }

    private fun look(activity: Activity, manual: Boolean) {
        val app = activity.applicationContext
        Thread {
            try {
                lookBlocking(activity, app, manual)
            } finally {
                looking = false
            }
        }.start()
    }

    private fun lookBlocking(activity: Activity, app: Context, manual: Boolean) {
        fun say(text: String) {
            if (manual) activity.runOnUiThread { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }
        try {
            val settings = SyncSettings(app)
            if (settings.baseUrl().isBlank() || settings.token().isBlank()) { say("Set the Oracle URL and token first"); return }
            val latest = fetchLatest(settings)
            val installed = installedCode(app)
            available = if (isNewerBuild(installed, latest.versionCode)) latest else null
            activity.runOnUiThread { onAvailableChanged?.invoke() }
            if (available == null) say("Up to date (build $installed)")
            else say("Update available: tap Update at the top of the home screen")
        } catch (error: Throwable) {
            lastCheckAt = 0L // retry on the next look instead of waiting
            Log.w(TAG, "Update check failed: ${error.message}")
            say("Update check failed: ${error.message}")
        }
    }

    /** The Update button: downloads the build found by [check], verifies it and installs it. */
    @Volatile private var installing = false

    fun installAvailable(activity: Activity) {
        val release = available ?: return
        if (installing) return // already downloading: a second tap must not start a second install
        val app = activity.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Allow \"Install unknown apps\" for this app, then tap Update again", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return
        }
        Toast.makeText(app, "Updating to build ${release.versionCode}…", Toast.LENGTH_LONG).show()
        installing = true
        Thread {
            try {
                install(app, SyncSettings(app), release)
                Thread.sleep(30_000) // the app is replaced when the install finishes; if not, allow a retry
                installing = false
            } catch (error: Throwable) {
                installing = false
                Log.w(TAG, "Update failed: ${error.message}")
                activity.runOnUiThread { Toast.makeText(app, "Update failed: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun open(settings: SyncSettings, path: String): HttpURLConnection =
        (URL(settings.baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("X-Sync-Token", settings.token())
        }

    private fun fetchLatest(settings: SyncSettings): AppRelease {
        val c = open(settings, "/api/v1/app/version")
        try {
            if (c.responseCode != 200) throw IOException("server answered HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return AppRelease(j.getLong("versionCode"), j.optString("versionName"), j.optString("sha256"))
        } finally {
            c.disconnect()
        }
    }

    private fun install(app: Context, settings: SyncSettings, release: AppRelease) {
        val apk = File(app.cacheDir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val c = open(settings, "/api/v1/app/apk")
        try {
            if (c.responseCode != 200) throw IOException("download failed (HTTP ${c.responseCode})")
            c.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256.isNotBlank() && !hex.equals(release.sha256, ignoreCase = true)) {
            apk.delete()
            throw IOException("checksum mismatch")
        }

        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("finance.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            registerReceiver(app)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), flags)
            session.commit(callback.intentSender)
        }
    }

    @Synchronized
    private fun registerReceiver(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (confirm != null) context.startActivity(confirm)
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    else -> Log.w(TAG, "Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }
}

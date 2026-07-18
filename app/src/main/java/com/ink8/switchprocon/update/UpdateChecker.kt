package com.ink8.switchprocon.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.ink8.switchprocon.BuildConfig
import com.ink8.switchprocon.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater for sideloaded installs. Every CI build is published as a permanent GitHub
 * Release tagged `build-<N>` with the signed APK attached. On launch we ask the GitHub API
 * for the latest release; if its build number beats our [BuildConfig.VERSION_CODE] we
 * offer to download and install it (Android always shows a final confirm screen for
 * sideloaded installs — that part can't be skipped).
 *
 * All builds are signed with the same committed keystore, so any newer APK installs
 * cleanly over any older one.
 */
class UpdateChecker(private val activity: Activity) {

    fun checkAsync() {
        Thread {
            try {
                val latest = fetchLatest() ?: return@Thread
                if (latest.buildNumber > BuildConfig.VERSION_CODE && latest.apkUrl != null) {
                    activity.runOnUiThread { offer(latest) }
                }
            } catch (e: Exception) {
                Log.i(TAG, "update check skipped: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    private data class Release(val buildNumber: Int, val name: String, val apkUrl: String?)

    private fun fetchLatest(): Release? {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null // e.g. no releases yet
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")            // "build-42"
            val number = tag.substringAfterLast('-').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            return Release(number, json.optString("name", tag), apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(release: Release) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available)
            .setMessage(activity.getString(R.string.update_message, release.name))
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(release: Release) {
        // Sideloaded updates need the "install unknown apps" grant for this app (once).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, R.string.update_allow_source, Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val apk = File(activity.cacheDir, "update.apk")
                (URL(release.apkUrl!!).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    instanceFollowRedirects = true
                }.inputStream.use { input ->
                    apk.outputStream().use { input.copyTo(it) }
                }
                val uri = FileProvider.getUriForFile(
                    activity, "${activity.packageName}.fileprovider", apk
                )
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(install)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val API_LATEST =
            "https://api.github.com/repos/ink8/switchprocon/releases/latest"
    }
}

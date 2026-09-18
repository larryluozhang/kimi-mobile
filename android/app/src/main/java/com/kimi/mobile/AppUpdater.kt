package com.kimi.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新：从 GitHub releases 的 latest.json 读取 android 段，
 * 与 BuildConfig.VERSION_CODE 比较；有新版则下载 APK 到 cacheDir/updates/，
 * 校验 sha256 后经 FileProvider 调起系统安装器。
 * 下载在调用线程执行（调用方需放到子线程），下载回调在同一线程触发。
 */
object AppUpdater {

    private const val MANIFEST_URL =
        "https://github.com/larryluozhang/kimi-mobile/releases/download/app-latest/latest.json"
    private const val FILE_PROVIDER_AUTHORITY = "com.kimi.mobile.fileprovider"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val url: String,
        val sha256: String,
        val notes: String
    )

    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    interface DownloadCallback {
        /** 已下载字节数 / 总字节数（-1 表示服务器未给 Content-Length） */
        fun onProgress(downloaded: Long, total: Long)
        fun onDone()
        fun onError(msg: String)
    }

    /** 子线程请求 latest.json，回调切回主线程。任何失败都走 Error，不抛异常。 */
    fun checkLatest(context: Context, cb: (CheckResult) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        CheckResult.Error("检查更新失败：HTTP ${conn.responseCode}")
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseManifest(body)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                CheckResult.Error("检查更新失败：${e.message ?: e.javaClass.simpleName}")
            }
            handler.post { cb(result) }
        }.start()
    }

    private fun parseManifest(body: String): CheckResult {
        val android = try {
            JSONObject(body).optJSONObject("android")
        } catch (e: Exception) {
            null
        } ?: return CheckResult.Error("检查更新失败：清单格式异常")
        val code = android.optInt("version_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return CheckResult.UpToDate
        return CheckResult.Available(
            UpdateInfo(
                versionName = android.optString("version_name"),
                versionCode = code,
                url = android.optString("url"),
                sha256 = android.optString("sha256"),
                notes = android.optString("notes")
            )
        )
    }

    /** 下载 APK 并校验 sha256，通过后调起系统安装器；成功 true。在调用线程执行。 */
    fun downloadAndInstall(context: Context, url: String, sha256: String, cb: DownloadCallback): Boolean {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update.apk")
        apk.delete()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    apk.delete()
                    cb.onError("下载失败：HTTP ${conn.responseCode}")
                    return false
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(apk).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            cb.onProgress(downloaded, total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (sha256.isNotBlank() && !sha256.equals(sha256Of(apk), ignoreCase = true)) {
                apk.delete()
                cb.onError("安装包校验失败，已取消")
                return false
            }

            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            cb.onDone()
            return true
        } catch (e: Exception) {
            apk.delete()
            cb.onError("下载失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 发现新版本对话框：更新日志最多显示 10 行；设置页手动检查与启动自动检查共用。 */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val notesPreview = info.notes.lines().take(10).joinToString("\n").trim()
        val tvNotes = TextView(activity).apply {
            text = if (notesPreview.isEmpty()) "暂无更新说明" else notesPreview
            textSize = 14f
            setPadding(48, 24, 48, 0)
        }
        val scroll = ScrollView(activity).apply {
            addView(tvNotes, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${info.versionName}")
            .setView(scroll)
            .setPositiveButton("立即更新") { _, _ -> startDownload(activity, info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 下载进度对话框：复用模型下载的 MB 进度文案；成功即调起安装器，失败 Toast 原因。 */
    private fun startDownload(activity: Activity, info: UpdateInfo) {
        val tvProgress = TextView(activity).apply {
            text = "准备下载…"
            textSize = 14f
            setPadding(48, 24, 48, 0)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("正在下载 ${info.versionName}")
            .setView(tvProgress)
            .setCancelable(false)
            .show()
        Thread {
            downloadAndInstall(activity, info.url, info.sha256, object : DownloadCallback {
                override fun onProgress(downloaded: Long, total: Long) {
                    val dl = downloaded / 1024.0 / 1024.0
                    val text = if (total > 0) {
                        "%.1f MB / %.1f MB".format(dl, total / 1024.0 / 1024.0)
                    } else {
                        "%.1f MB".format(dl)
                    }
                    activity.runOnUiThread { tvProgress.text = text }
                }

                override fun onDone() {
                    activity.runOnUiThread { dlg.dismiss() }
                }

                override fun onError(msg: String) {
                    activity.runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }.start()
    }
}

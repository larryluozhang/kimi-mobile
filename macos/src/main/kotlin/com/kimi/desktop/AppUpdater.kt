package com.kimi.desktop

import androidx.compose.runtime.snapshots.Snapshot
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * 应用内自更新：从 GitHub Releases 固定地址拉 latest.json（按操作系统选 macos/windows 节点），
 * 版本比 AppVersion.CURRENT 新 → macOS 下载 DMG（SHA-256 校验）退出并脚本重装；Windows 打开浏览器手动下载替换。
 */
object AppUpdater {
    private const val MANIFEST_URL =
        "https://github.com/larryluozhang/kimi-mobile/releases/download/app-latest/latest.json"
    private const val INSTALLED_APP = "/Applications/Kimi Mobile.app"
    private const val APP_NAME = "Kimi Mobile"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 3600 * 1000

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /** latest.json 中本机对应的节点名 */
    private val manifestNode = if (isWindows) "windows" else "macos"

    data class UpdateInfo(
        val version: String,
        val url: String,
        val sha256: String,
        val notes: String
    )

    sealed class CheckResult {
        data class Update(val info: UpdateInfo) : CheckResult()
        object None : CheckResult() // 已是最新
        data class Error(val message: String) : CheckResult()
    }

    /** 点分版本号逐段数值比较（1.0.9 < 1.0.22，字符串比较会出错）；缺段按 0，非数字段按 0 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.')
        val pb = b.trim().removePrefix("v").split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

    /** 后台线程拉 latest.json；回调统一包 withMutableSnapshot 派发（调用方在回调里写 Compose 快照状态是安全的） */
    fun checkLatest(cb: (CheckResult) -> Unit) {
        Thread({
            val result = try {
                AppLog.log("UPDATE", "检查更新 $MANIFEST_URL (当前 ${AppVersion.CURRENT})")
                val body = httpGet(MANIFEST_URL, connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
                val node = JSONObject(body).optJSONObject(manifestNode)
                if (node == null) {
                    CheckResult.Error("更新清单缺少 $manifestNode 节点")
                } else {
                    val info = UpdateInfo(
                        version = node.optString("version"),
                        url = node.optString("url"),
                        sha256 = node.optString("sha256"),
                        notes = node.optString("notes")
                    )
                    if (info.version.isEmpty() || info.url.isEmpty()) {
                        CheckResult.Error("更新清单字段不完整")
                    } else if (isNewer(info.version, AppVersion.CURRENT)) {
                        AppLog.log("UPDATE", "发现新版本 ${info.version}")
                        CheckResult.Update(info)
                    } else {
                        AppLog.log("UPDATE", "已是最新（远端 ${info.version}）")
                        CheckResult.None
                    }
                }
            } catch (e: Exception) {
                AppLog.error("UPDATE", "检查更新失败", e)
                CheckResult.Error("检查更新失败：${e.message ?: e.javaClass.simpleName}")
            }
            dispatch { cb(result) }
        }, "kimi-update-check").apply { isDaemon = true }.start()
    }

    /** 后台线程回调里写 Compose 快照状态的合法通道（裸写会抛 snapshot 未应用异常） */
    private fun dispatch(block: () -> Unit) = Snapshot.withMutableSnapshot { block() }

    /** 启动时自动检查：距上次检查满 24h 才发起；失败/无更新均静默，仅发现新版本时回调 */
    fun autoCheckOnLaunch(onUpdate: (UpdateInfo) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheckMs() < AUTO_CHECK_INTERVAL_MS) return
        Prefs.setLastUpdateCheckMs(now)
        checkLatest { r ->
            if (r is CheckResult.Update) onUpdate(r.info)
        }
    }

    /**
     * 下载 DMG 到临时文件并校验 SHA-256（边下边算）；progressCb(已下载字节, 总字节[-1 未知])。
     * 校验失败删除临时文件并走 errCb。
     */
    fun download(
        url: String,
        sha256: String,
        progressCb: (downloaded: Long, total: Long) -> Unit,
        doneCb: (File) -> Unit,
        errCb: (String) -> Unit
    ) {
        Thread({
            val tmp = File.createTempFile("kimi-update-", ".dmg")
            try {
                AppLog.log("UPDATE", "开始下载 $url -> ${tmp.absolutePath}")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            if (downloaded - lastReported >= 256 * 1024) { // 约 0.25MB 报一次，避免刷屏
                                lastReported = downloaded
                                dispatch { progressCb(downloaded, total) }
                            }
                        }
                        dispatch { progressCb(downloaded, total) }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha256.isNotEmpty() && !actual.equals(sha256.trim(), ignoreCase = true)) {
                    tmp.delete()
                    AppLog.log("UPDATE", "SHA-256 校验失败：期望 $sha256 实际 $actual")
                    dispatch { errCb("安装包校验失败（SHA-256 不匹配），已删除") }
                } else {
                    AppLog.log("UPDATE", "下载完成并校验通过：${tmp.absolutePath} (${total / 1024 / 1024}MB)")
                    dispatch { doneCb(tmp) }
                }
            } catch (e: Exception) {
                tmp.delete()
                AppLog.error("UPDATE", "下载失败", e)
                dispatch { errCb("下载失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }, "kimi-update-download").apply { isDaemon = true }.start()
    }

    /**
     * 写临时 bash 脚本并脱离本进程启动：等本进程退出 → 挂载 DMG → 替换 /Applications/Kimi Mobile.app →
     * 卸载 → 重新打开；随后本进程立即退出。脚本输出落 ~/.kimi-mobile/update.log 便于排查。
     */
    fun installAndRelaunch(dmgFile: File) {
        val pid = ProcessHandle.current().pid()
        val script = File.createTempFile("kimi-update-install-", ".sh")
        script.writeText(
            """
            |#!/bin/bash
            |exec >> "${'$'}HOME/.kimi-mobile/update.log" 2>&1
            |echo "== ${'$'}(date) 开始安装 ${dmgFile.absolutePath} =="
            |while kill -0 $pid 2>/dev/null; do sleep 0.5; done
            |MNT="${'$'}(mktemp -d /tmp/kimi-update-mount.XXXXXX)"
            |hdiutil attach -nobrowse -mountpoint "${'$'}MNT" "${dmgFile.absolutePath}" || exit 1
            |rm -rf "$INSTALLED_APP"
            |cp -R "${'$'}MNT"/*.app /Applications/ || exit 1
            |hdiutil detach "${'$'}MNT" -quiet || hdiutil detach "${'$'}MNT" -force -quiet
            |rm -rf "${'$'}MNT"
            |rm -f "${dmgFile.absolutePath}"
            |open -a "$APP_NAME"
            |echo "== ${'$'}(date) 安装完成 =="
            |
            """.trimMargin()
        )
        AppLog.log("UPDATE", "启动安装脚本 ${script.absolutePath}，本进程 $pid 即将退出")
        ProcessBuilder("/bin/bash", script.absolutePath).start()
        exitProcess(0)
    }

    private fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}

package com.kimi.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线语音资源下载器：HttpURLConnection 下载 zip 到 filesDir/tmp，
 * 再用 java.util.zip 解压到目标目录（模型 4 件套 → filesDir/models/zipformer-bilingual/；
 * 原生引擎 4 个 .so → filesDir/native-engine/1.13.7/）。
 * 全部在调用线程执行（调用方需放到子线程），回调也在同一线程触发。
 */
object ModelDownloader {

    interface Callback {
        /** 已下载字节数 / 总字节数（-1 表示服务器未给 Content-Length） */
        fun onProgress(downloaded: Long, total: Long)
        fun onDone()
        fun onError(msg: String)
    }

    /** 下载并解压模型；成功 true。任何失败都会清理半成品（tmp 文件、不完整的模型目录）。 */
    fun download(context: Context, urlText: String, cb: Callback): Boolean =
        downloadAndExtract(
            context, urlText,
            zipName = "model-zipformer-bilingual.zip",
            targetDir = SpeechOnnx.modelDir(context),
            expectedFiles = SpeechOnnx.MODEL_FILES,
            missingLabel = "压缩包缺少模型文件",
            cb = cb,
        )

    /** 下载并解压原生引擎（4 个 .so 平铺 zip）到 NativeEngine 目录；成功 true。 */
    fun downloadEngine(context: Context, urlText: String, cb: Callback): Boolean =
        downloadAndExtract(
            context, urlText,
            zipName = "sherpa-onnx-engine.zip",
            targetDir = NativeEngine.engineDir(context),
            expectedFiles = NativeEngine.ENGINE_FILES,
            missingLabel = "压缩包缺少引擎文件",
            cb = cb,
        )

    /** 下载 zip 到 filesDir/tmp，按文件名匹配解压 expectedFiles 到 targetDir（兼容 zip 内带一层目录）。 */
    private fun downloadAndExtract(
        context: Context,
        urlText: String,
        zipName: String,
        targetDir: File,
        expectedFiles: List<String>,
        missingLabel: String,
        cb: Callback,
    ): Boolean {
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val zipFile = File(tmpDir, zipName)
        try {
            // ---- 下载 ----
            val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total: Long
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    cb.onError("下载失败：HTTP ${conn.responseCode}")
                    return false
                }
                total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zipFile).use { out ->
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

            // ---- 解压：按文件名匹配（兼容 zip 内带一层目录的打包方式）----
            val found = HashSet<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = File(entry.name).name
                    if (name !in expectedFiles) continue
                    targetDir.mkdirs()
                    FileOutputStream(File(targetDir, name)).use { out ->
                        zis.copyTo(out)
                    }
                    found.add(name)
                }
            }
            val missing = expectedFiles.filter { it !in found }
            if (missing.isNotEmpty()) {
                targetDir.deleteRecursively()
                cb.onError("$missingLabel：${missing.joinToString()}")
                return false
            }
            zipFile.delete()
            cb.onDone()
            return true
        } catch (e: Exception) {
            // 解压中途失败可能留下不完整文件，清掉避免可用性误判
            if (!expectedFiles.all { File(targetDir, it).isFile }) targetDir.deleteRecursively()
            zipFile.delete()
            cb.onError("下载失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
    }
}

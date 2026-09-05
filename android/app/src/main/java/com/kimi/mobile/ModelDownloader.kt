package com.kimi.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线语音模型下载器：HttpURLConnection 下载 zip 到 filesDir/tmp，
 * 再用 java.util.zip 解压出模型 4 件套到 filesDir/models/zipformer-bilingual/。
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
    fun download(context: Context, urlText: String, cb: Callback): Boolean {
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val zipFile = File(tmpDir, "model-zipformer-bilingual.zip")
        val modelDir = SpeechOnnx.modelDir(context)
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

            // ---- 解压：按文件名匹配 4 件套（兼容 zip 内带一层目录的打包方式）----
            val found = HashSet<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = File(entry.name).name
                    if (name !in SpeechOnnx.MODEL_FILES) continue
                    modelDir.mkdirs()
                    FileOutputStream(File(modelDir, name)).use { out ->
                        zis.copyTo(out)
                    }
                    found.add(name)
                }
            }
            val missing = SpeechOnnx.MODEL_FILES.filter { it !in found }
            if (missing.isNotEmpty()) {
                modelDir.deleteRecursively()
                cb.onError("压缩包缺少模型文件：${missing.joinToString()}")
                return false
            }
            zipFile.delete()
            cb.onDone()
            return true
        } catch (e: Exception) {
            // 解压中途失败可能留下不完整模型，清掉避免 isModelAvailable 误判
            if (!SpeechOnnx.isModelAvailable(context)) modelDir.deleteRecursively()
            zipFile.delete()
            cb.onError("下载失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
    }
}

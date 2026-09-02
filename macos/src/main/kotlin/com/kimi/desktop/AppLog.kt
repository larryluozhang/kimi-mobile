package com.kimi.desktop

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 文件日志：本机无屏幕录制/终端观测手段，app 运行状态全部落 ~/.kimi-mobile/app.log */
object AppLog {
    private val file = File(System.getProperty("user.home"), ".kimi-mobile/app.log")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    fun log(tag: String, msg: String) {
        val line = "${LocalDateTime.now().format(fmt)} [${Thread.currentThread().name}] $tag: $msg\n"
        synchronized(lock) {
            try {
                file.parentFile.mkdirs()
                FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) { }
        }
    }

    fun error(tag: String, msg: String, e: Throwable) {
        log(tag, "$msg -> ${e.javaClass.name}: ${e.message}")
        log(tag, e.stackTraceToString().take(3000))
    }

    fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            error("CRASH", "uncaught on thread ${t.name}", e)
        }
    }
}

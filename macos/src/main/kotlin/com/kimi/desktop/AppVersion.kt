package com.kimi.desktop

import java.util.Properties

/** 应用版本号；唯一来源是 build.gradle.kts 的 version，经 generateVersionProperties 写入资源 */
object AppVersion {
    val CURRENT: String by lazy {
        runCatching {
            val p = Properties()
            AppVersion::class.java.getResourceAsStream("/version.properties")?.use { p.load(it) }
            p.getProperty("version")
        }.getOrNull() ?: "unknown"
    }
}

package com.kimi.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) {
    AppLog.installCrashHandler()
    AppLog.log("APP", "启动 java=${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")} args=${args.toList()}")
    if (args.contains("--e2e")) {
        val idle = args.firstOrNull { it.startsWith("--e2e-idle=") }
            ?.substringAfter('=')?.toIntOrNull() ?: 0
        E2e.run(idle) // 无窗口链路自检，跑完即退出
    }
    if (args.contains("--e2e-profile")) {
        E2e.runProfileTest() // 会话模式档自检（/tmp 新会话），跑完即退出
    }
    application {
        val state = remember {
            AppLog.log("APP", "AppState init: profiles=${Prefs.profiles().size} server=${Prefs.serverUrl()} tokenLen=${Prefs.token().length} model=${Prefs.model()}")
            AppState()
        }
        val icon = painterResource("icon.png")

    Window(
        onCloseRequest = {
            state.stopWs()
            exitApplication()
        },
        title = "Kimi Mobile",
        icon = icon,
        state = rememberWindowState(width = 1080.dp, height = 720.dp)
    ) {
        KimiTheme {
            when (state.screen) {
                Screen.Gate -> GateScreen(state)
                Screen.Main -> MainScreen(state)
            }
        }
    }

    if (state.settingsOpen) {
        Window(
            onCloseRequest = {
                state.settingsOpen = false
                state.authError = null
            },
            title = "设置 - Kimi Mobile",
            icon = icon,
            state = rememberWindowState(width = 560.dp, height = 600.dp)
        ) {
            KimiTheme {
                SettingsScreen(state)
            }
        }
    }
}
}

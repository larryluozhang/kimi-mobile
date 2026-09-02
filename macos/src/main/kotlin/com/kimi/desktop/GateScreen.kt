package com.kimi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Tailscale 门控页：每 5s 探测 healthz，通了自动进主界面 */
@Composable
fun GateScreen(state: AppState) {
    var tailscaleMissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AppLog.log("GATE", "门控协程启动，目标 ${state.server()}")
        while (true) {
            state.gateRetrying = true
            state.gateMessage = "正在检测 Tailscale 连接…"
            val code = try {
                withContext(Dispatchers.IO) { Api.healthz(state.server()) }
            } catch (e: Throwable) {
                AppLog.error("GATE", "healthz 抛出异常（已被吞掉过？现在记录并继续重试）", e)
                -1
            }
            AppLog.log("GATE", "healthz -> $code")
            if (code > 0) {
                // 服务可达即放行（401 说明网络通、token 有问题，进主界面后会引导去设置）
                state.screen = Screen.Main
                break
            }
            state.gateRetrying = false
            state.gateMessage = "无法连接 ${state.server()}\n请确认 Tailscale 已连接后重试（每 5 秒自动重试）"
            delay(5000)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Kimi Mobile", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            if (state.gateRetrying) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(16.dp))
            }
            Text(
                state.gateMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                try {
                    Runtime.getRuntime().exec(arrayOf("open", "-a", "Tailscale"))
                    tailscaleMissing = false
                } catch (e: Exception) {
                    tailscaleMissing = true
                }
            }) {
                Text("打开 Tailscale")
            }
            if (tailscaleMissing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "未找到 Tailscale 应用，请先到 https://tailscale.com 下载安装",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

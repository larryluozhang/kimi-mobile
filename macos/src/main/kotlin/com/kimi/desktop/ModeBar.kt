package com.kimi.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

val MODEL_OPTIONS = listOf(
    "kimi-code/k3",
    "kimi-code/kimi-for-coding",
    "kimi-code/kimi-for-coding-highspeed",
    "kimi-code/k3-256k"
)

val PERMISSION_OPTIONS = listOf("manual", "auto", "yolo")

private fun modelLabel(m: String) = m.removePrefix("kimi-code/")

private fun permissionLabel(p: String) = when (p) {
    "manual" -> "手动"
    "auto" -> "自动"
    "yolo" -> "YOLO"
    else -> p
}

/** 修改即 POST；成功后以预期值更新本地状态并持久化（服务端 v0.35.0 不回显 agent_config） */
private fun patchProfile(state: AppState, scope: CoroutineScope, sessionId: String, patch: JSONObject, updated: Api.SessionProfile) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                Api.updateProfile(state.server(), state.token(), sessionId, patch)
            }
            AppLog.log("PROFILE", "更新已接受: $patch")
            state.sessionProfile = updated
            Prefs.saveSessionMode(sessionId, updated)
        } catch (e: ApiException) {
            AppLog.error("PROFILE", "更新失败", e)
            if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
            else {
                state.chatError = e.message
                state.messages.add(ChatMessage("err-" + System.currentTimeMillis(), "error", "模式设置失败：${e.message}"))
            }
        } catch (e: Throwable) {
            AppLog.error("PROFILE", "更新异常", e)
            state.chatError = e.message
            state.messages.add(ChatMessage("err-" + System.currentTimeMillis(), "error", "模式设置失败：${e.message}"))
        }
    }
}

@Composable
fun ModeBar(state: AppState, scope: CoroutineScope, sessionId: String) {
    val profile = state.sessionProfile
    var permMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (profile == null) {
                Text(
                    if (state.profileLoading) "模式加载中…" else "模式不可用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Row
            }

            // 计划模式
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = profile.planMode,
                    onCheckedChange = { patchProfile(state, scope, sessionId, JSONObject().put("plan_mode", it), profile.copy(planMode = it)) },
                    modifier = Modifier.height(28.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text("计划", fontSize = 12.sp)
            }

            // Swarm
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = profile.swarmMode,
                    onCheckedChange = { patchProfile(state, scope, sessionId, JSONObject().put("swarm_mode", it), profile.copy(swarmMode = it)) },
                    modifier = Modifier.height(28.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text("Swarm", fontSize = 12.sp)
            }

            // 权限模式
            Box {
                OutlinedButton(onClick = { permMenu = true }) {
                    Text("权限:${permissionLabel(profile.permissionMode)}", fontSize = 12.sp)
                }
                DropdownMenu(expanded = permMenu, onDismissRequest = { permMenu = false }) {
                    for (p in PERMISSION_OPTIONS) {
                        DropdownMenuItem(
                            text = { Text("${permissionLabel(p)}（$p）") },
                            onClick = {
                                permMenu = false
                                if (p != profile.permissionMode) {
                                    patchProfile(state, scope, sessionId, JSONObject().put("permission_mode", p), profile.copy(permissionMode = p))
                                }
                            }
                        )
                    }
                }
            }

            // 模型
            Box {
                OutlinedButton(onClick = { modelMenu = true }) {
                    Text(modelLabel(profile.model.ifEmpty { state.model }), fontSize = 12.sp)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    for (m in MODEL_OPTIONS) {
                        DropdownMenuItem(
                            text = { Text(m, fontSize = 13.sp) },
                            onClick = {
                                modelMenu = false
                                if (m != profile.model) {
                                    patchProfile(state, scope, sessionId, JSONObject().put("model", m), profile.copy(model = m))
                                }
                            }
                        )
                    }
                }
            }

            // 目标模式
            if (profile.goalObjective.isEmpty()) {
                TextButton(onClick = { goalDialog = true }) { Text("＋目标", fontSize = 12.sp) }
            } else {
                Text(
                    "目标:${profile.goalObjective.take(12)}${if (profile.goalObjective.length > 12) "…" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { patchProfile(state, scope, sessionId, JSONObject().put("goal_control", "pause"), profile) }) { Text("暂停", fontSize = 12.sp) }
                TextButton(onClick = { patchProfile(state, scope, sessionId, JSONObject().put("goal_control", "resume"), profile) }) { Text("恢复", fontSize = 12.sp) }
                TextButton(onClick = { patchProfile(state, scope, sessionId, JSONObject().put("goal_control", "cancel"), profile.copy(goalObjective = "")) }) {
                    Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (goalDialog) {
        var goalText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { goalDialog = false },
            title = { Text("设定目标") },
            text = {
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    placeholder = { Text("描述这个会话要完成的目标…") },
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        goalDialog = false
                        val text = goalText.trim()
                        val profile = state.sessionProfile ?: return@Button
                        patchProfile(state, scope, sessionId, JSONObject().put("goal_objective", text), profile.copy(goalObjective = text))
                    },
                    enabled = goalText.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { goalDialog = false }) { Text("取消") } }
        )
    }
}

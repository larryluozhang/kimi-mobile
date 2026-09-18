package com.kimi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.UUID

@Composable
fun SettingsScreen(state: AppState) {
    var editing by remember { mutableStateOf<HostProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }

    // 打开设置页时拉取服务端模型列表（失败/为空回退内置预设）
    LaunchedEffect(Unit) { loadModelList(state) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)

            state.authError?.let {
                Spacer(Modifier.height(8.dp))
                Surface(color = StatusBg, shape = MaterialTheme.shapes.small) {
                    Text(
                        "$it（请检查下方 Token）",
                        color = StatusText,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("主机档案", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.profiles, key = { it.id }) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Prefs.setActiveProfile(p.id)
                                state.reloadPrefs()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p.id == state.activeProfileId, onClick = {
                            Prefs.setActiveProfile(p.id)
                            state.reloadPrefs()
                        })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, fontSize = 14.sp)
                            Text(
                                p.url,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { editing = p; showEditor = true }) { Text("编辑") }
                        TextButton(onClick = {
                            Prefs.deleteProfile(p.id)
                            state.reloadPrefs()
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    editing = HostProfile(UUID.randomUUID().toString(), "", "", "")
                    showEditor = true
                }) { Text("新增主机") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    importMsg = null
                    showImport = true
                }) { Text("导入服务器") }
            }
            importMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            Text("模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { modelMenu = true }) {
                        Text(modelDisplayName(state, state.model), fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        if (state.modelItems.isNotEmpty()) {
                            for (item in state.modelItems) {
                                DropdownMenuItem(
                                    text = { Text(item.displayName, fontSize = 13.sp) },
                                    onClick = {
                                        modelMenu = false
                                        Prefs.setModel(item.model)
                                        state.reloadPrefs()
                                    }
                                )
                            }
                        } else {
                            for (m in MODEL_OPTIONS) {
                                DropdownMenuItem(
                                    text = { Text(m, fontSize = 13.sp) },
                                    onClick = {
                                        modelMenu = false
                                        Prefs.setModel(m)
                                        state.reloadPrefs()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "默认 ${Prefs.DEFAULT_MODEL}；发送消息时作为请求体顶层 model 字段（会话模式栏另选则以会话为准）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "版本 ${AppVersion.CURRENT}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        checkingUpdate = true
                        updateMsg = null
                        AppUpdater.checkLatest { r ->
                            checkingUpdate = false
                            when (r) {
                                is AppUpdater.CheckResult.Update -> updateInfo = r.info
                                AppUpdater.CheckResult.None -> updateMsg = "已是最新版本"
                                is AppUpdater.CheckResult.Error -> updateMsg = r.message
                            }
                        }
                    },
                    enabled = !checkingUpdate
                ) { Text(if (checkingUpdate) "检查中…" else "检查更新", fontSize = 12.sp) }
            }
            updateMsg?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showEditor && editing != null) {
        ProfileEditorDialog(
            initial = editing!!,
            onDismiss = { showEditor = false },
            onSave = { p ->
                Prefs.upsertProfile(p)
                state.reloadPrefs()
                state.authError = null
                showEditor = false
            }
        )
    }

    if (showImport) {
        ImportServerDialog(
            onDismiss = { showImport = false },
            onImported = { msg ->
                state.reloadPrefs()
                state.authError = null
                importMsg = msg
                showImport = false
            }
        )
    }

    updateInfo?.let { info ->
        UpdateDialog(info, onDismiss = { updateInfo = null })
    }
}

/** 更新对话框：设置页手动检查与启动自动检查共用；macOS 下载校验后 installAndRelaunch 退出重装，Windows 打开浏览器手动下载替换 */
@Composable
fun UpdateDialog(info: AppUpdater.UpdateInfo, onDismiss: () -> Unit) {
    var downloading by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var browserOpened by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    when {
        error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = { Text(error!!, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
        browserOpened -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${info.version}") },
            text = { Text("已打开下载页面，下载后解压替换原目录即可。", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
        downloadedFile != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("下载完成") },
            text = {
                Text(
                    "新版本 ${info.version} 已下载并通过校验。点击「立即安装」后应用将退出并自动重装。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(onClick = { AppUpdater.installAndRelaunch(downloadedFile!!) }) { Text("立即安装") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
        )
        downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("正在下载更新") },
            text = { Text(progressText, fontSize = 13.sp) },
            confirmButton = { }
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column {
                    if (info.notes.isNotBlank()) {
                        Column(
                            Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                        ) { Text(info.notes, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (AppUpdater.isWindows) "将打开浏览器下载，下载后解压替换原目录即可。"
                        else "更新过程中应用将退出并自动重装。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (AppUpdater.isWindows) {
                        try {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(info.url))
                            browserOpened = true
                        } catch (e: Exception) {
                            error = "打开浏览器失败：${e.message ?: e.javaClass.simpleName}"
                        }
                    } else {
                        downloading = true
                        progressText = "正在连接…"
                        AppUpdater.download(
                            url = info.url,
                            sha256 = info.sha256,
                            progressCb = { done, total ->
                                val mb = done / 1024.0 / 1024.0
                                progressText = if (total > 0) {
                                    "已下载 %.1f MB / %.1f MB".format(mb, total / 1024.0 / 1024.0)
                                } else {
                                    "已下载 %.1f MB".format(mb)
                                }
                            },
                            doneCb = { f -> downloading = false; downloadedFile = f },
                            errCb = { msg -> downloading = false; error = msg }
                        )
                    }
                }) { Text("立即更新") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
        )
    }
}

@Composable
private fun ProfileEditorDialog(initial: HostProfile, onDismiss: () -> Unit, onSave: (HostProfile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf(initial.token) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "新增主机" else "编辑主机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(HostProfile(initial.id, name.trim(), url.trim(), token.trim())) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 导入服务器：粘贴 kimi-mobile://connect deeplink 或 JSON 卡片，解析成功即建档并切换为当前主机；同 URL 已存在则只更新 token */
@Composable
private fun ImportServerDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    placeholder = { Text("粘贴 kimi-mobile://connect 链接或 JSON 卡片") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    isError = error != null
                )
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val imported = parseServerImport(text)
                        val normalized = imported.url.trimEnd('/')
                        val existing = Prefs.profiles()
                            .firstOrNull { it.url.trim().trimEnd('/') == normalized }
                        val msg: String
                        val activeId: String
                        if (existing != null) {
                            Prefs.upsertProfile(existing.copy(token = imported.token))
                            activeId = existing.id
                            msg = "已更新「${existing.name}」的 Token 并切换为当前主机"
                        } else {
                            val profile = HostProfile(
                                id = "import-${System.currentTimeMillis()}",
                                name = imported.name,
                                url = imported.url,
                                token = imported.token
                            )
                            Prefs.upsertProfile(profile)
                            activeId = profile.id
                            msg = "已导入「${imported.name}」并切换为当前主机"
                        }
                        Prefs.setActiveProfile(activeId)
                        onImported(msg)
                    } catch (e: ImportParseException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "导入失败：${e.message ?: e.javaClass.simpleName}"
                    }
                },
                enabled = text.isNotBlank()
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

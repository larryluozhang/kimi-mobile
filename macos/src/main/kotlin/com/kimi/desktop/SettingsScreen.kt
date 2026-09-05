package com.kimi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun SettingsScreen(state: AppState) {
    var editing by remember { mutableStateOf<HostProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var modelText by remember(state.settingsOpen) { mutableStateOf(state.model) }

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

            OutlinedButton(onClick = {
                editing = HostProfile(UUID.randomUUID().toString(), "", "", "")
                showEditor = true
            }) { Text("新增主机") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            Text("模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    Prefs.setModel(modelText)
                    state.reloadPrefs()
                }) { Text("保存") }
            }
            Text(
                "默认 ${Prefs.DEFAULT_MODEL}；发送消息时作为请求体顶层 model 字段",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            Text(
                "版本 ${AppVersion.CURRENT}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

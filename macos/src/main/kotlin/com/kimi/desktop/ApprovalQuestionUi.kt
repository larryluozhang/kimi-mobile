package com.kimi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 待审批卡片：显示工具名/动作/摘要，批准或拒绝（POST 成功后 agent 恢复运行） */
@Composable
fun ApprovalCard(state: AppState, scope: CoroutineScope, sessionId: String, item: Api.ApprovalItem) {
    var submitting by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StatusBg)
                .padding(12.dp)
        ) {
            Text(
                "审批请求：${item.toolName.ifEmpty { "tool" }}" + if (item.action.isNotEmpty()) "（${item.action}）" else "",
                fontSize = 13.sp,
                color = StatusText
            )
            if (item.summary.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(item.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !submitting,
                    onClick = {
                        submitting = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Api.respondApproval(state.server(), state.token(), sessionId, item.id, approved = true)
                                }
                                AppLog.log("APPROVAL", "已批准 ${item.toolName} (${item.id})")
                                state.pendingApprovals.removeAll { it.id == item.id }
                            } catch (e: ApiException) {
                                AppLog.error("APPROVAL", "批准失败", e)
                                if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                                else state.chatError = e.message
                                submitting = false
                            } catch (e: Throwable) {
                                AppLog.error("APPROVAL", "批准异常", e)
                                state.chatError = e.message
                                submitting = false
                            }
                        }
                    }
                ) { Text("批准", fontSize = 13.sp) }
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        submitting = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Api.respondApproval(state.server(), state.token(), sessionId, item.id, approved = false)
                                }
                                AppLog.log("APPROVAL", "已拒绝 ${item.toolName} (${item.id})")
                                state.pendingApprovals.removeAll { it.id == item.id }
                            } catch (e: ApiException) {
                                AppLog.error("APPROVAL", "拒绝失败", e)
                                if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                                else state.chatError = e.message
                                submitting = false
                            } catch (e: Throwable) {
                                AppLog.error("APPROVAL", "拒绝异常", e)
                                state.chatError = e.message
                                submitting = false
                            }
                        }
                    }
                ) { Text("拒绝", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private const val OTHER_OPTION = "__other__"

/**
 * 待回答卡片：单选 RadioButton 组；allow_other 时追加"其他"+文本输入；
 * "跳过"以 kind=skipped 提交。提交后 agent 恢复运行，turn 继续。
 */
@Composable
fun QuestionCard(state: AppState, scope: CoroutineScope, sessionId: String, item: Api.PendingQuestion) {
    // 每题的选择：选项 id，或 OTHER_OPTION（选择"其他"）
    val selections = remember(item.id) { mutableStateMapOf<String, String>() }
    val otherTexts = remember(item.id) { mutableStateMapOf<String, String>() }
    var submitting by remember(item.id) { mutableStateOf(false) }

    val allAnswered = item.questions.all { q ->
        val sel = selections[q.id]
        sel != null && (sel != OTHER_OPTION || otherTexts[q.id]?.isNotBlank() == true)
    }

    fun submit(answers: JSONObject) {
        submitting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Api.answerQuestion(state.server(), state.token(), sessionId, item.id, answers)
                }
                AppLog.log("QUESTION", "已提交回答 ${item.id}")
                state.pendingQuestions.removeAll { it.id == item.id }
            } catch (e: ApiException) {
                AppLog.error("QUESTION", "提交失败", e)
                if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                else state.chatError = e.message
                submitting = false
            } catch (e: Throwable) {
                AppLog.error("QUESTION", "提交异常", e)
                state.chatError = e.message
                submitting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text("助手向你提问", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            // 题目/选项区限高可滚动：内容多时底部提交按钮始终可达
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
            for (q in item.questions) {
                Spacer(Modifier.height(6.dp))
                if (q.header.isNotEmpty()) {
                    Text(q.header, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(q.question, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                val options = if (q.allowOther) q.options + Api.QuestionOption(OTHER_OPTION, "其他", "") else q.options
                for (o in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .selectable(
                                selected = selections[q.id] == o.id,
                                onClick = { selections[q.id] = o.id }
                            )
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = selections[q.id] == o.id,
                            onClick = { selections[q.id] = o.id }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(o.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            if (o.description.isNotEmpty()) {
                                Text(o.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (q.allowOther && selections[q.id] == OTHER_OPTION) {
                    OutlinedTextField(
                        value = otherTexts[q.id] ?: "",
                        onValueChange = { otherTexts[q.id] = it },
                        placeholder = { Text("请输入…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp)
                    )
                }
            }
            } // 可滚动题目区结束
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !submitting && allAnswered,
                    onClick = {
                        val answers = JSONObject()
                        for (q in item.questions) {
                            val sel = selections[q.id] ?: continue
                            if (sel == OTHER_OPTION) {
                                answers.put(
                                    q.id,
                                    JSONObject()
                                        .put("kind", "other")
                                        .put("text", (otherTexts[q.id] ?: "").trim())
                                )
                            } else {
                                answers.put(
                                    q.id,
                                    JSONObject()
                                        .put("kind", "single")
                                        .put("option_id", sel)
                                )
                            }
                        }
                        submit(answers)
                    }
                ) { Text("提交", fontSize = 13.sp) }
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        val answers = JSONObject()
                        for (q in item.questions) {
                            answers.put(q.id, JSONObject().put("kind", "skipped"))
                        }
                        submit(answers)
                    }
                ) { Text("跳过", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

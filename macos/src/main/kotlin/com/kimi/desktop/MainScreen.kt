package com.kimi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(state: AppState) {
    val scope = rememberCoroutineScope()

    // 初始加载工作区 + 会话
    LaunchedEffect(state.activeProfileId) {
        loadSidebar(state, state.activeWorkspaceId)
        // 自动选中最近会话：用户进主界面即看到历史，同时建立 WS 长连接（可观测）
        if (state.activeSessionId.isEmpty()) {
            val visible = state.sessions.filter {
                state.activeWorkspaceId.isEmpty() || it.workspaceId.isEmpty() || it.workspaceId == state.activeWorkspaceId
            }
            visible.firstOrNull()?.let {
                AppLog.log("MAIN", "自动选中会话 ${it.id}")
                state.activeSessionId = it.id
            }
        }
    }

    // 定时刷新侧边栏（30s）：其他会话的 busy 徽标需要周期对齐服务端状态，
    // 否则已停止的会话会一直显示"运行中"。historyLoading/会话加载中途跳过，避免打断当前 UI
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (!state.historyLoading && !state.sessionsLoading) {
                loadSidebar(state, state.activeWorkspaceId)
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sidebar(state, scope, modifier = Modifier.width(280.dp).fillMaxHeight())
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        ChatPane(state, scope, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

/** token 数格式化：>=1000 显示为 k（23500 → "23.5k"，1000000 → "1000k"） */
private fun formatTokenCount(n: Long): String =
    if (n >= 1000) {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k" else "%.1fk".format(k)
    } else "$n"

/** 上下文使用量展示：如 "23.5k/1000k (2%)"；max<=0（服务端未报上限）时只显示用量 */
private fun formatContextUsage(contextTokens: Long, maxContextTokens: Long): String =
    if (maxContextTokens > 0) {
        val pct = contextTokens * 100 / maxContextTokens
        "${formatTokenCount(contextTokens)}/${formatTokenCount(maxContextTokens)} ($pct%)"
    } else {
        formatTokenCount(contextTokens)
    }

private suspend fun loadSidebar(state: AppState, preferWorkspaceId: String = "") {
    state.sessionsLoading = true
    state.sidebarError = null
    AppLog.log("MAIN", "loadSidebar 开始")
    try {
        val (ws, ss) = withContext(Dispatchers.IO) {
            val w = Api.listWorkspaces(state.server(), state.token())
            val s = Api.listSessions(state.server(), state.token())
            w to s
        }
        AppLog.log("MAIN", "loadSidebar 成功: workspaces=${ws.size} sessions=${ss.size}")
        state.workspaces.clear()
        state.workspaces.addAll(ws)
        state.sessions.clear()
        state.sessions.addAll(ss)
        val remembered = preferWorkspaceId.ifEmpty { Prefs.lastWorkspaceId() ?: "" }
        if (state.workspaces.isNotEmpty() && state.workspaces.none { it.id == state.activeWorkspaceId }) {
            state.activeWorkspaceId =
                state.workspaces.firstOrNull { it.id == remembered }?.id ?: state.workspaces.first().id
        }
    } catch (e: ApiException) {
        AppLog.error("MAIN", "loadSidebar ApiException", e)
        if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
        else state.sidebarError = e.message
    } catch (e: Throwable) {
        AppLog.error("MAIN", "loadSidebar 异常", e)
        state.sidebarError = e.message ?: "加载失败"
    } finally {
        state.sessionsLoading = false
    }
}

/** 重拉服务端历史与排队/执行中列表并调和本地回显（失败静默，不打扰当前界面）；返回队列快照供 busy 轮询判活 */
private suspend fun refreshHistory(state: AppState, sessionId: String): Api.PromptQueue? {
    return try {
        val history = withContext(Dispatchers.IO) {
            Api.getMessages(state.server(), state.token(), sessionId)
        }
        val prompts = fetchPromptQueue(state, sessionId)
        // 跨会话竞态防护：拉取期间用户已切走则丢弃本次结果，别把别的会话内容写进当前视图
        if (state.activeSessionId != sessionId) {
            AppLog.log("RECONCILE", "拉取完成时已切到 ${state.activeSessionId.take(24)}，丢弃 ${sessionId.take(24)} 的结果")
            return null
        }
        state.reconcileHistory(
            history.map { ChatMessage(it.id, it.role, it.text, timeMillis = isoToMillis(it.createdAt)) },
            sessionId,
            prompts.queued,
            prompts.active
        )
        prompts
    } catch (e: Exception) {
        AppLog.error("MAIN", "refreshHistory 失败", e)
        null
    }
}

/** 拉服务端排队/执行中的 prompt 快照；失败返回空快照（不影响历史调和） */
private suspend fun fetchPromptQueue(state: AppState, sessionId: String): Api.PromptQueue =
    withContext(Dispatchers.IO) {
        try {
            Api.listQueuedPrompts(state.server(), state.token(), sessionId)
        } catch (e: Exception) {
            AppLog.error("MAIN", "拉取排队列表失败", e)
            Api.PromptQueue(emptyList(), null)
        }
    }

@Composable
private fun Sidebar(state: AppState, scope: kotlinx.coroutines.CoroutineScope, modifier: Modifier) {
    var wsMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        // 顶栏：品牌 + 设置
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Kimi Mobile",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { state.settingsOpen = true }) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // 工作区切换器
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { wsMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    state.activeWorkspace()?.name ?: "（无工作区）",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = wsMenuOpen, onDismissRequest = { wsMenuOpen = false }) {
                for (w in state.workspaces) {
                    DropdownMenuItem(
                        text = { Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            state.activeWorkspaceId = w.id
                            Prefs.setLastWorkspaceId(w.id)
                            wsMenuOpen = false
                        }
                    )
                }
            }
        }

        // 操作行：新建 / 刷新
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            val s = withContext(Dispatchers.IO) {
                                Api.createSession(state.server(), state.token(), state.activeWorkspace())
                            }
                            loadSidebar(state, state.activeWorkspaceId)
                            state.activeSessionId = s.id
                        } catch (e: ApiException) {
                            if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                            else state.sidebarError = e.message
                        } catch (e: Exception) {
                            state.sidebarError = e.message
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新会话", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { scope.launch { loadSidebar(state, state.activeWorkspaceId) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        state.sidebarError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (state.sessionsLoading) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        // 会话列表（按当前工作区过滤；无工作区信息时显示全部）
        val visible = state.sessions.filter {
            state.activeWorkspaceId.isEmpty() || it.workspaceId.isEmpty() || it.workspaceId == state.activeWorkspaceId
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { s ->
                val selected = s.id == state.activeSessionId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.activeSessionId = s.id }
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (s.busy) {
                            // 运行中标记：小圆点 + 文案
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "运行中",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (s.updatedAt.isNotEmpty()) {
                        Text(
                            s.updatedAt.take(16).replace('T', ' '),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ChatPane(state: AppState, scope: kotlinx.coroutines.CoroutineScope, modifier: Modifier) {
    val sessionId = state.activeSessionId
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 切换会话：加载历史 + 服务端排队列表 + 建立 WS 订阅
    // 注意：不清 pendingEchoes（按 sessionId 隔离，切走再切回时本端未确认回显仍在）
    LaunchedEffect(sessionId) {
        if (sessionId.isEmpty()) return@LaunchedEffect
        state.stopWs()
        state.messages.clear()
        state.frames.clear()
        state.pendingApprovals.clear()
        state.pendingQuestions.clear()
        state.chatError = null
        state.contextTokens = -1
        state.maxContextTokens = -1
        state.busy = state.sessions.firstOrNull { it.id == sessionId }?.busy ?: false
        state.historyLoading = true
        try {
            val history = withContext(Dispatchers.IO) {
                Api.getMessages(state.server(), state.token(), sessionId)
            }
            // 以服务端队列为真相来源重建排队/执行中气泡（含其他端提交/重启前排队的消息）
            val prompts = fetchPromptQueue(state, sessionId)
            if (state.activeSessionId != sessionId) return@LaunchedEffect // 拉取期间已切走
            state.reconcileHistory(
                history.map { ChatMessage(it.id, it.role, it.text, timeMillis = isoToMillis(it.createdAt)) },
                sessionId,
                prompts.queued,
                prompts.active
            )
        } catch (e: ApiException) {
            if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
            else state.chatError = e.message
        } catch (e: Exception) {
            state.chatError = e.message
        } finally {
            state.historyLoading = false
        }

        // 会话模式档：本地持久化状态为准（v0.35.0 服务端 GET /profile 不回显，仅硬编码占位），
        // 本地无记录时用服务端回显（未来服务端版本可能返回真实值），仍无则给默认
        state.sessionProfile = null
        state.profileLoading = true
        try {
            val remote = withContext(Dispatchers.IO) { Api.getProfile(state.server(), state.token(), sessionId) }
            AppLog.log("PROFILE", "GET 回显 plan=${remote.planMode} swarm=${remote.swarmMode} perm=${remote.permissionMode} model='${remote.model}' goal='${remote.goalObjective.take(20)}'")
            val local = Prefs.sessionMode(sessionId)
            state.sessionProfile = local ?: remote
        } catch (e: ApiException) {
            AppLog.error("PROFILE", "加载失败", e)
            if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
            else state.chatError = e.message
            state.sessionProfile = Prefs.sessionMode(sessionId)
        } catch (e: Throwable) {
            AppLog.error("PROFILE", "加载异常", e)
            state.sessionProfile = Prefs.sessionMode(sessionId)
        } finally {
            state.profileLoading = false
            if (state.sessionProfile == null) {
                state.sessionProfile = Api.SessionProfile("", "", "manual", false, false, "", "")
            }
        }

        // 上下文使用量兜底：WS reset 快照/meta.merge 未上报时，GET /sessions/{id} usage（实测可能全 0）
        try {
            val usage = withContext(Dispatchers.IO) { Api.getSessionUsage(state.server(), state.token(), sessionId) }
            if (usage != null && state.activeSessionId == sessionId && state.contextTokens < 0) {
                state.contextTokens = usage.first
                state.maxContextTokens = usage.second
            }
        } catch (e: Throwable) {
            AppLog.error("MAIN", "拉取会话 usage 失败", e)
        }

        var wsOpenedOnce = false
        val ws = WsClient(state.server(), state.token(), sessionId, object : WsClient.Listener {
            override fun onOpen() {
                state.wsConnected = true
                // 重连后对齐一次历史 + 侧边栏（首次连接前刚加载过，跳过）；回显由 reconcileHistory 保留
                if (wsOpenedOnce) {
                    scope.launch {
                        refreshHistory(state, sessionId)
                        loadSidebar(state, state.activeWorkspaceId)
                    }
                }
                wsOpenedOnce = true
            }
            override fun onClosed() { state.wsConnected = false }
            override fun onAuthError() { state.onAuthFailure("WebSocket 认证失败：Token 无效或已过期") }
            override fun onError(message: String) { state.chatError = message }
            override fun onWorkChanged(busy: Boolean) {
                val wasBusy = state.busy
                state.busy = busy
                // busy 刚消失：立即做最后一次历史对齐（迟订阅收不到该 turn 的 turn.upsert，实测 v0.37.2）
                if (wasBusy && !busy) {
                    scope.launch {
                        refreshHistory(state, sessionId)
                        loadSidebar(state, state.activeWorkspaceId)
                    }
                }
            }
            override fun onPhase(kind: String, stream: String) {
                state.phase = kind
                // reset 快照/meta.merge 带来的实时阶段：据此立即亮"工作中"状态，打开忙会话不再死寂
                when (kind) {
                    "ended", "idle" -> state.busy = false
                    "" -> {}
                    else -> state.busy = true
                }
            }
            override fun onFrameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String) {
                AppLog.log("WS", "frame.upsert kind=$kind role=$role len=${text.length}: ${text.take(80)}")
                // 幻影消息：系统注入的 user 帧不显示
                if (Api.isPhantomUserText(role, text)) return
                val idx = state.frames.indexOfFirst { it.frameId == frameId }
                val f = StreamFrame(frameId, kind, role, text)
                if (idx >= 0) state.frames[idx] = f else state.frames.add(f)
            }
            override fun onToolFrame(turnId: String, frameId: String, name: String, toolState: String, summary: String) {
                AppLog.log("WS", "tool frame name=$name state=$toolState: ${summary.take(60)}")
                val idx = state.frames.indexOfFirst { it.frameId == frameId }
                val f = StreamFrame(frameId, "tool", "assistant", summary, name = name, state = toolState)
                if (idx >= 0) state.frames[idx] = f else state.frames.add(f)
            }
            override fun onFrameAppend(frameId: String, offset: Long, text: String) {
                val idx = state.frames.indexOfFirst { it.frameId == frameId } ?: return
                if (idx >= 0) {
                    val f = state.frames[idx]
                    if (f.kind == "tool") return // 工具帧摘要由 frame.upsert 整体更新
                    state.frames[idx] = f.copy(text = f.text + text)
                }
            }
            override fun onTurnState(turnState: String, error: String?) {
                when (turnState) {
                    "running" -> state.busy = true
                    "completed", "failed", "cancelled" -> {
                        state.busy = false
                        if (error != null) state.chatError = error
                        // turn 结束：重拉历史对齐（未确认回显保留），清空流式缓冲
                        scope.launch {
                            refreshHistory(state, sessionId)
                            state.frames.clear()
                            loadSidebar(state, state.activeWorkspaceId)
                        }
                    }
                }
            }
            override fun onTranscriptReset() { state.frames.clear() }
            override fun onContextUsage(contextTokens: Long, maxContextTokens: Long) {
                state.contextTokens = contextTokens
                if (maxContextTokens > 0) state.maxContextTokens = maxContextTokens
            }
        })
        state.wsClient = ws
        ws.start()
    }

    DisposableEffect(sessionId) {
        onDispose { state.stopWs() }
    }

    // 周期轮询历史（含队列调和）：busy 时 15s 一轮（迟订阅的 WS 收不到进行中 turn 的任何
    // transcript.ops，含 turn.upsert 完成事件，实测 v0.37.2），空闲时 60s 一轮兜底对齐。
    // 轮询发现服务端已无 active/queued（busy 消失但 work_changed 事件缺失）时落 busy 并做最后一次对齐。
    // LaunchedEffect(sessionId) 保证同一会话只有一个轮询，切会话即取消
    LaunchedEffect(sessionId) {
        if (sessionId.isEmpty()) return@LaunchedEffect
        while (true) {
            // busy 时 15s 一轮（迟订阅收不到 turn 事件，靠轮询在 turn 完成 15s 内看到回复）；
            // 空闲时 60s 一轮兜底对齐——WS 稳定后不再有重连刷新，空闲会话的气泡状态
            // （排队/执行中/未送达）只能靠周期调和保持新鲜
            delay(if (state.busy) 15_000 else 60_000)
            val prompts = refreshHistory(state, sessionId) ?: continue
            // 本地还有未确认回显（POST 在途/待确认）时不能据此判 busy 消失
            val hasPending = state.pendingEchoes.any { it.sessionId == sessionId && !it.undelivered }
            if (state.busy && !hasPending && prompts.active == null && prompts.queued.isEmpty()) {
                AppLog.log("MAIN", "轮询发现 busy 消失（无 active/queued），做最后一次对齐")
                state.busy = false
                refreshHistory(state, sessionId)
                loadSidebar(state, state.activeWorkspaceId)
            }
        }
    }

    // 轮询待审批/待回答（5s 一轮）：agent 卡审批或等待问答时无 WS 帧也无 history 变化，只能靠轮询发现。
    // 拉取期间切会话则丢弃结果；失败静默（不清空已有卡片，避免 UI 闪烁——真已被处理时下轮返回空列表自然消失）
    LaunchedEffect(sessionId) {
        if (sessionId.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(5_000)
            try {
                val (approvals, questions) = withContext(Dispatchers.IO) {
                    Api.listPendingApprovals(state.server(), state.token(), sessionId) to
                        Api.listPendingQuestions(state.server(), state.token(), sessionId)
                }
                if (state.activeSessionId != sessionId) return@LaunchedEffect // 轮询期间已切走
                state.pendingApprovals.clear()
                state.pendingApprovals.addAll(approvals)
                state.pendingQuestions.clear()
                state.pendingQuestions.addAll(questions)
            } catch (e: ApiException) {
                AppLog.error("MAIN", "审批/问答轮询失败", e)
                if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
            } catch (e: Throwable) {
                AppLog.error("MAIN", "审批/问答轮询异常", e)
            }
        }
    }

    // 新内容自动滚到底
    val itemCount = state.messages.size + state.frames.size
    LaunchedEffect(itemCount, state.frames.lastOrNull()?.text) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // / 命令与头部按钮共用的失败处理（引用它的 UI 在下方定义，须提前声明）
        val slashFail: (Throwable, String) -> Unit = { e, action ->
            AppLog.error("SLASH", "$action 失败", e)
            if (e is ApiException && (e.httpCode == 401 || e.httpCode == 403)) state.onAuthFailure(e.message ?: "认证失败")
            else {
                state.chatError = e.message
                state.messages.add(ChatMessage("err-" + System.currentTimeMillis(), "error", "$action 失败：${e.message}"))
            }
        }
        // 分叉当前会话：头部「分叉」按钮与 /fork 命令同一路径
        val doFork: () -> Unit = {
            scope.launch {
                try {
                    val newId = withContext(Dispatchers.IO) { Api.forkSession(state.server(), state.token(), sessionId) }
                    AppLog.log("FORK", "已分叉会话 newId=${newId.take(24)}")
                    loadSidebar(state, state.activeWorkspaceId)
                    if (newId.isNotEmpty()) state.activeSessionId = newId
                    else state.chatError = "分叉成功，但服务端未返回新会话 id"
                } catch (e: Throwable) { slashFail(e, "分叉会话") }
            }
        }
        // 聊天头部
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = state.sessions.firstOrNull { it.id == sessionId }?.title ?: "未选择会话"
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // 上下文使用量（WS reset 快照/meta.merge 上报，GET session usage 兜底）
                if (state.contextTokens >= 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatContextUsage(state.contextTokens, state.maxContextTokens),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.busy || state.pendingEchoes.any { it.sessionId == sessionId && !it.undelivered }) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.pendingEchoes.any { it.sessionId == sessionId && it.queued } -> "排队中…"
                            state.phase == "streaming" -> "正在输出…"
                            else -> "正在思考…"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    // 中断按钮：busy 时显示，点击中断当前 turn（POST :abort，实测返回 {"aborted":true}）
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Api.abortSession(state.server(), state.token(), sessionId)
                                }
                                AppLog.log("MAIN", "已发送中断请求 session=${sessionId.take(24)}")
                            } catch (e: ApiException) {
                                AppLog.error("MAIN", "中断失败", e)
                                if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                                else state.chatError = e.message
                            } catch (e: Throwable) {
                                AppLog.error("MAIN", "中断异常", e)
                                state.chatError = e.message
                            }
                        }
                    }) {
                        Text("停止", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                // 分叉按钮：与 /fork 命令同一路径（Api.forkSession + 成功后切到新会话）
                if (sessionId.isNotEmpty()) {
                    TextButton(onClick = { doFork() }) {
                        Text("分叉", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // 会话模式栏
        if (sessionId.isNotEmpty()) {
            ModeBar(state, scope, sessionId)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        // 计划/Swarm 模式视觉提示
        val modeHints = buildList {
            if (state.sessionProfile?.planMode == true) add("计划模式：助手只规划不执行")
            if (state.sessionProfile?.swarmMode == true) add("Swarm 模式：多智能体并行")
            if (state.sessionProfile?.goalObjective?.isNotEmpty() == true) add("目标模式")
        }
        if (modeHints.isNotEmpty()) {
            Surface(color = StatusBg) {
                Text(
                    modeHints.joinToString(" ｜ "),
                    color = StatusText,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        state.chatError?.let {
            Surface(color = StatusBg) {
                Text(it, color = StatusText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }

        // 消息区
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (sessionId.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("选择左侧会话，或点击「新会话」开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                    items(state.messages, key = { "h-" + it.id }) { m ->
                        MessageBubble(m.role, m.text, queued = m.queued, executing = m.executing, undelivered = m.undelivered)
                    }
                    items(state.frames, key = { "f-" + it.frameId }) { f ->
                        if (f.kind == "tool") {
                            ToolActivityRow(f.name, f.text, done = f.state == "done")
                        } else {
                            val role = if (f.kind == "thinking") "thinking" else "assistant"
                            MessageBubble(role, f.text, streaming = true)
                        }
                    }
                    if (state.historyLoading) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }

        // 待审批 / 待回答卡片：agent 挂起等人类决策时显示，响应后服务端恢复 turn
        for (a in state.pendingApprovals) {
            ApprovalCard(state, scope, sessionId, a)
        }
        for (q in state.pendingQuestions) {
            QuestionCard(state, scope, sessionId, q)
        }

        // 输入区
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        // / 命令帮助弹窗（/help 触发）
        var slashHelpOpen by remember { mutableStateOf(false) }
        // / 命令：发送前拦截，精确匹配（忽略大小写）；返回 true 表示已消费，未识别的 / 开头文本当普通 prompt 发送
        val handleSlash: (String) -> Boolean = { raw ->
            // 按首 token 匹配命令（/fork xxx 也算 /fork），与 CLI 行为一致
            when (raw.trim().lowercase().substringBefore(' ').substringBefore('\n')) {
                "/compact" -> {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api.compactSession(state.server(), state.token(), sessionId) }
                            AppLog.log("SLASH", "已压缩会话历史 session=${sessionId.take(24)}")
                            state.messages.add(ChatMessage("sys-" + System.currentTimeMillis(), "assistant", "✅ 会话历史已压缩"))
                            refreshHistory(state, sessionId)
                        } catch (e: Throwable) { slashFail(e, "压缩会话") }
                    }
                    true
                }
                "/archive" -> {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api.archiveSession(state.server(), state.token(), sessionId) }
                            AppLog.log("SLASH", "已归档会话 session=${sessionId.take(24)}")
                            // 归档后回到会话列表：清掉当前会话并刷新侧边栏
                            state.activeSessionId = ""
                            loadSidebar(state, state.activeWorkspaceId)
                        } catch (e: Throwable) { slashFail(e, "归档会话") }
                    }
                    true
                }
                "/fork" -> {
                    doFork()
                    true
                }
                "/rename", "/title" -> {
                    // 取首个空格后全部剩余文本作为新标题
                    val newTitle = raw.trim().substringAfter(' ', "").trim()
                    if (newTitle.isEmpty()) {
                        state.messages.add(
                            ChatMessage("sys-" + System.currentTimeMillis(), "assistant", "用法：/rename 新会话名（/title 同义）")
                        )
                    } else {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Api.renameSession(state.server(), state.token(), sessionId, newTitle)
                                }
                                AppLog.log("SLASH", "已改名会话 title=$newTitle session=${sessionId.take(24)}")
                                state.messages.add(
                                    ChatMessage("sys-" + System.currentTimeMillis(), "assistant", "✅ 会话已改名为「$newTitle」")
                                )
                                loadSidebar(state, state.activeWorkspaceId)
                            } catch (e: Throwable) { slashFail(e, "会话改名") }
                        }
                    }
                    true
                }
                "/abort", "/stop" -> {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api.abortSession(state.server(), state.token(), sessionId) }
                            AppLog.log("SLASH", "已发送中断请求 session=${sessionId.take(24)}")
                        } catch (e: Throwable) { slashFail(e, "中断会话") }
                    }
                    true
                }
                "/new" -> {
                    // 与侧边栏「新会话」按钮同一流程
                    scope.launch {
                        try {
                            val s = withContext(Dispatchers.IO) {
                                Api.createSession(state.server(), state.token(), state.activeWorkspace())
                            }
                            loadSidebar(state, state.activeWorkspaceId)
                            state.activeSessionId = s.id
                        } catch (e: Throwable) { slashFail(e, "新建会话") }
                    }
                    true
                }
                "/help" -> { slashHelpOpen = true; true }
                else -> false
            }
        }
        if (slashHelpOpen) {
            AlertDialog(
                onDismissRequest = { slashHelpOpen = false },
                confirmButton = { TextButton(onClick = { slashHelpOpen = false }) { Text("知道了") } },
                title = { Text("斜杠命令") },
                text = {
                    Text(
                        """
                        /compact — 压缩当前会话历史
                        /archive — 归档当前会话并返回会话列表
                        /fork — 以当前会话为起点分叉出新会话并切换过去
                        /rename（或 /title）新名字 — 修改当前会话标题
                        /abort（或 /stop）— 中断当前正在运行的 turn
                        /new — 新建会话
                        /help — 显示本说明

                        其他以 / 开头的文本会作为普通消息发送。
                        """.trimIndent()
                    )
                }
            )
        }
        // 回车发送（Shift+Enter 换行），与发送按钮共用同一逻辑
        val doSend: () -> Unit = {
            val text = input.trim()
            if (text.isNotEmpty() && sessionId.isNotEmpty()) {
                input = ""
                if (text.startsWith("/") && handleSlash(text)) {
                    AppLog.log("SEND", "执行命令: $text")
                } else {
                val echoId = "local-" + System.currentTimeMillis()
                state.messages.add(ChatMessage(echoId, "user", text, timeMillis = System.currentTimeMillis()))
                state.busy = true
                AppLog.log("SEND", "发送消息 len=${text.length} session=$sessionId")
                scope.launch {
                    try {
                        val status = withContext(Dispatchers.IO) {
                            Api.sendPrompt(state.server(), state.token(), sessionId, text, state.model, state.sessionProfile)
                        }
                        // 回显登记为待确认（按会话隔离）：历史刷新时按文本确认移除；queued 期间头部提示"排队中"
                        state.pendingEchoes.add(PendingEcho(echoId, sessionId, text, queued = status == "queued"))
                        AppLog.log("SEND", "发送成功 status=$status，等待 WS 流式回复")
                    } catch (e: ApiException) {
                        state.busy = false
                        state.removeEcho(echoId)
                        AppLog.error("SEND", "发送失败", e)
                        if (e.httpCode == 401 || e.httpCode == 403) state.onAuthFailure(e.message ?: "认证失败")
                        else {
                            state.chatError = e.message
                            // 业务错误以错误气泡形式明确告知用户
                            state.messages.add(
                                ChatMessage("err-" + System.currentTimeMillis(), "error", "发送失败：${e.message}")
                            )
                        }
                    } catch (e: Throwable) {
                        state.busy = false
                        state.removeEcho(echoId)
                        AppLog.error("SEND", "发送异常", e)
                        state.chatError = e.message
                        state.messages.add(
                            ChatMessage("err-" + System.currentTimeMillis(), "error", "发送失败：${e.message}")
                        )
                    }
                }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                        doSend()
                        true
                    } else false
                },
                placeholder = { Text(if (sessionId.isEmpty()) "先选择会话" else "输入消息…（Enter 发送，Shift+Enter 换行）") },
                enabled = sessionId.isNotEmpty(),
                maxLines = 5,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { doSend() },
                enabled = sessionId.isNotEmpty() && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

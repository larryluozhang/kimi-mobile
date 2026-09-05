package com.kimi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { Gate, Main }

data class ChatMessage(
    val id: String,
    val role: String, // user / assistant / thinking
    val text: String,
    val queued: Boolean = false, // user 气泡：服务端排队中标记
    val executing: Boolean = false, // user 气泡：服务端正在执行（data.active，v0.37.2 起不在 queued[] 里）
    val undelivered: Boolean = false, // user 气泡：服务端已丢弃（幻影 busy 时排队 prompt 被静默丢弃，上游 #3127）
    val timeMillis: Long = 0 // 排序用；0=未知（排到最后）
)

/** ISO 时间戳（带偏移或 Z）转毫秒；失败返回 0 */
internal fun isoToMillis(s: String): Long {
    if (s.isEmpty()) return 0L
    return try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { 0L }
    }
}

/** 流式帧（WS transcript.ops 驱动）；kind=tool 时 name/state 有效，text 为摘要 */
data class StreamFrame(
    val frameId: String,
    val kind: String, // text / thinking / tool
    val role: String,
    val text: String,
    val name: String = "",
    val state: String = "" // tool: running / done
)

/** 本地乐观回显（待服务端历史确认）；按 sessionId 隔离，queued=true 表示服务端排队中 */
data class PendingEcho(
    val id: String,
    val sessionId: String,
    val text: String,
    val queued: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val executing: Boolean = false, // 服务端正在执行（data.active 匹配）
    val undelivered: Boolean = false // 超 60s 既不在历史也不在队列 → 服务端已丢弃
)

class AppState {
    var screen by mutableStateOf(Screen.Gate)
    var gateMessage by mutableStateOf("正在检测 Tailscale 连接…")
    var gateRetrying by mutableStateOf(false)

    var settingsOpen by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)

    var profiles by mutableStateOf(Prefs.profiles())
    var activeProfileId by mutableStateOf(Prefs.activeProfile()?.id ?: "")
    var model by mutableStateOf(Prefs.model())

    var workspaces = mutableStateListOf<WorkspaceItem>()
    var activeWorkspaceId by mutableStateOf("")
    var sessions = mutableStateListOf<SessionItem>()
    var activeSessionId by mutableStateOf("")
    var sessionsLoading by mutableStateOf(false)
    var sidebarError by mutableStateOf<String?>(null)

    var messages = mutableStateListOf<ChatMessage>()
    var frames = mutableStateListOf<StreamFrame>()
    /** 已发送但未在服务端历史中确认的本地回显（排队中的消息不进历史，刷新时要保留） */
    var pendingEchoes = mutableStateListOf<PendingEcho>()
    var historyLoading by mutableStateOf(false)
    /** 上一页原始 items 数达到 page_size → 前面可能还有更早历史 */
    var historyHasMore by mutableStateOf(false)
    /** 「加载更早消息」进行中 */
    var olderLoading by mutableStateOf(false)
    /** 加载过至少一页更早历史（用于区分"没有更多了"与短会话不提示） */
    var olderLoadedOnce by mutableStateOf(false)
    /** 分页加载的更早历史（比首页最旧消息还早）；reconcileHistory 全量刷新时保留在头部 */
    private val olderHistory = ArrayList<ChatMessage>()
    var busy by mutableStateOf(false)
    var phase by mutableStateOf("")
    var wsConnected by mutableStateOf(false)
    var chatError by mutableStateOf<String?>(null)

    /** 当前会话上下文使用量（token）；<0 表示未知。来源优先级：WS reset 快照/meta.merge > GET session usage 兜底 */
    var contextTokens by mutableStateOf(-1L)
    var maxContextTokens by mutableStateOf(-1L)

    /** 待审批的工具调用 / 待回答的问题（轮询每 5s 更新；agent 在 manual 权限下发起需审批工具调用时挂起，等本端决策后继续 */
    var pendingApprovals = mutableStateListOf<Api.ApprovalItem>()
    var pendingQuestions = mutableStateListOf<Api.PendingQuestion>()

    var wsClient: WsClient? = null

    /** 当前会话的模式档（agent_config）；null=未加载 */
    var sessionProfile by mutableStateOf<Api.SessionProfile?>(null)
    var profileLoading by mutableStateOf(false)

    fun server() = Prefs.serverUrl()
    fun token() = Prefs.token()

    fun activeWorkspace(): WorkspaceItem? =
        workspaces.firstOrNull { it.id == activeWorkspaceId } ?: workspaces.firstOrNull()

    fun activeProfile(): HostProfile? = profiles.firstOrNull { it.id == activeProfileId }

    /** 配置（主机档案/模型）在设置窗口被修改后调用 */
    fun reloadPrefs() {
        profiles = Prefs.profiles()
        activeProfileId = Prefs.activeProfile()?.id ?: ""
        model = Prefs.model()
    }

    fun onAuthFailure(message: String) {
        authError = message
        settingsOpen = true
    }

    /**
     * 用服务端全量历史刷新消息列表，并以服务端队列为真相来源调和本地回显：
     * - 历史中已出现相同文本的 user 消息 → 回显确认移除；
     * - 服务端队列里有的 → 回显标记为排队中；本地没有回显的队列条目（其他端提交/重启后）→ 直接显示为排队中气泡；
     * - activePrompt（data.active，当前执行中）匹配的回显 → 标记"执行中"（不再是排队中）；执行中的消息绝不标"未送达"；
     *   无本地回显的 active 条目 → 重建为"执行中"气泡；
     * - 既不在历史也不在队列/执行中的本地回显：存在未超 60s（POST 在途）→ 原样保留；
     *   超 60s → 视为被服务端丢弃（幻影 busy 丢队列 prompt，上游 MoonshotAI/kimi-code#3127），标记"未送达"；
     * - 队列重建气泡（queued-$sessionId-hash）无本地时间戳：本轮队列与历史都没有 → 直接消失（若真在队列下轮会重建）。
     * 只处理 sessionId 对应会话的回显；其他会话的回显不受影响。
     */
    fun reconcileHistory(history: List<ChatMessage>, sessionId: String, queuedPrompts: List<Api.QueuedPrompt>, activePrompt: Api.QueuedPrompt? = null) {
        fun sameText(a: String, b: String) = a.trim() == b.trim()
        val now = System.currentTimeMillis()
        // 诊断日志（2026-08-20 徽标错乱排查）：记录调和输入与每个回显的判定
        AppLog.log(
            "RECONCILE",
            "session=${sessionId.take(24)} history=${history.size} queued=${queuedPrompts.map { it.text.take(15) }} " +
                "active=${activePrompt?.text?.take(15)} echoes=${pendingEchoes.filter { it.sessionId == sessionId }.map { it.text.take(15) }}"
        )
        // 输出按时间排序（旧→新；无时间戳的未知项排最后），修复回显/排队/执行中气泡
        // 无条件堆在列表末尾导致的对话顺序错乱
        // 分页加载的更早历史保留在头部（时间戳更早，排序后自然在最前；按 id 去重防页边界重叠）
        val out = ArrayList((olderHistory + history).distinctBy { it.id })
        // 历史已确认 → 移除（仅限本会话回显）
        pendingEchoes.removeAll { p ->
            p.sessionId == sessionId && history.any { it.role == "user" && sameText(it.text, p.text) }
        }
        // 队列确认 → 更新 queued 标记（本地回显与队列条目同文本时只显示这一份）；
        // active 匹配 → 标记执行中（替代排队中）；active 中的消息绝不标未送达；
        // 不在队列/执行中且超 60s → 标记未送达（不再显示"排队中"假状态）
        val mine = pendingEchoes.filter { it.sessionId == sessionId }
        for (p in mine) {
            val inQueue = queuedPrompts.any { sameText(it.text, p.text) }
            val isActive = activePrompt != null && sameText(activePrompt.text, p.text)
            val undelivered = !inQueue && !isActive && now - p.createdAt > 60_000
            val queued = inQueue && !isActive && !undelivered
            val executing = isActive
            if (queued != p.queued || executing != p.executing || undelivered != p.undelivered) {
                AppLog.log("RECONCILE", "echo「${p.text.take(15)}」→ queued=$queued executing=$executing undelivered=$undelivered")
                val idx = pendingEchoes.indexOfFirst { it.id == p.id }
                if (idx >= 0) pendingEchoes[idx] = p.copy(queued = queued, executing = executing, undelivered = undelivered)
            }
        }
        for (p in pendingEchoes.filter { it.sessionId == sessionId }) {
            out.add(ChatMessage(p.id, "user", p.text, queued = p.queued, executing = p.executing, undelivered = p.undelivered, timeMillis = p.createdAt))
        }
        // active 里有、历史与本地回显都没有的条目（其他端提交的执行中 prompt）→ 执行中气泡
        if (activePrompt != null) {
            val confirmed = history.any { it.role == "user" && sameText(it.text, activePrompt.text) }
            val echoed = pendingEchoes.any { it.sessionId == sessionId && sameText(it.text, activePrompt.text) }
            if (!confirmed && !echoed) {
                out.add(ChatMessage("active-$sessionId-${activePrompt.text.hashCode()}", "user", activePrompt.text, executing = true, timeMillis = isoToMillis(activePrompt.createdAt)))
            }
        }
        // 队列里有、历史与本地回显都没有的条目 → 排队中气泡
        for (q in queuedPrompts) {
            val confirmed = history.any { it.role == "user" && sameText(it.text, q.text) }
            val echoed = pendingEchoes.any { it.sessionId == sessionId && sameText(it.text, q.text) }
            if (!confirmed && !echoed) {
                out.add(ChatMessage("queued-$sessionId-${q.text.hashCode()}", "user", q.text, queued = true, timeMillis = isoToMillis(q.createdAt)))
            }
        }
        messages.clear()
        messages.addAll(out.sortedBy { if (it.timeMillis > 0) it.timeMillis else Long.MAX_VALUE })
    }

    /** 发送失败：移除本地回显 */
    fun removeEcho(id: String) {
        pendingEchoes.removeAll { it.id == id }
        messages.removeAll { it.id == id }
    }

    /**
     * 前插一页更早历史（时间戳均早于当前列表首条，直接插到头部即可，无需重排）；
     * 按 id 去重（before_id 页边界或服务端包含式返回时不产生重复气泡）。
     */
    fun prependOlderHistory(older: List<ChatMessage>) {
        val known = HashSet<String>(olderHistory.size + messages.size)
        olderHistory.forEach { known.add(it.id) }
        messages.forEach { known.add(it.id) }
        val fresh = older.filter { it.id.isNotEmpty() && known.add(it.id) }
        if (fresh.isEmpty()) return
        olderHistory.addAll(fresh)
        messages.addAll(0, fresh)
    }

    /** 切换会话时重置分页状态（不清 pendingEchoes，按 sessionId 隔离） */
    fun resetOlderHistory() {
        olderHistory.clear()
        historyHasMore = false
        olderLoading = false
        olderLoadedOnce = false
    }

    fun stopWs() {
        wsClient?.stop()
        wsClient = null
        wsConnected = false
    }
}

package com.kimi.desktop

import org.json.JSONArray
import org.json.JSONObject

class ApiException(val httpCode: Int, message: String) : Exception(message)

data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: String,
    val busy: Boolean,
    val workspaceId: String
)

data class WorkspaceItem(
    val id: String,
    val name: String,
    val root: String
)

data class HistoryMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: String = ""
)

/** 与 Android 版 Api.kt 相同的接口与协议；传输层换成本机可用的 MiniHttp（NIO） */
object Api {

    /** 返回 HTTP code；-1 表示连接失败 */
    fun healthz(server: String): Int {
        return try {
            MiniHttp.get("$server/api/v1/healthz", timeoutMs = 3000).code
        } catch (e: Exception) {
            -1
        }
    }

    private fun checkAuth(code: Int, body: String): JSONObject {
        if (code == 401 || code == 403) throw ApiException(code, "Token 无效或已过期（HTTP $code）")
        if (code != 200) throw ApiException(code, "HTTP $code: ${body.take(200)}")
        val obj = JSONObject(body)
        val code0 = obj.optInt("code", 0)
        if (code0 != 0) {
            // 服务端把业务错误包在 HTTP 200 里返回；记录 body 摘要便于排查
            AppLog.log("HTTP", "业务错误 code=$code0 body=${body.take(300)}")
            throw ApiException(200, obj.optString("msg", "服务器返回错误 code=$code0"))
        }
        return obj.optJSONObject("data") ?: JSONObject()
    }

    /** 幻影消息：系统注入的 user 文本（官方 UI 不显示），整条隐藏 */
    fun isPhantomUserText(role: String, text: String): Boolean {
        if (role != "user") return false
        val t = text.trimStart()
        return t.startsWith("<system-reminder>") || t.startsWith("<cron-fire") || t.startsWith("<notification")
    }

    private fun getData(server: String, token: String, path: String): JSONObject {
        val resp = MiniHttp.get("$server$path", mapOf("Authorization" to "Bearer $token"))
        return checkAuth(resp.code, resp.body)
    }

    private fun postData(server: String, token: String, path: String, payload: JSONObject): JSONObject {
        val resp = MiniHttp.post("$server$path", mapOf("Authorization" to "Bearer $token"), payload.toString())
        return checkAuth(resp.code, resp.body)
    }

    fun listSessions(server: String, token: String): List<SessionItem> {
        // 不传 busy 过滤：busy=false 会把运行中/卡审批的会话从列表滤掉（用户感知为“会话丢失”）
        val data = getData(server, token, "/api/v1/sessions?page_size=50&include_archive=false&exclude_empty=false&archived_only=false")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<SessionItem>()
        for (i in 0 until items.length()) {
            val s = items.getJSONObject(i)
            var title = s.optString("title", "")
            if (title.isEmpty()) title = s.optString("last_prompt", "")
            if (title.isEmpty()) title = "（未命名会话）"
            out.add(
                SessionItem(
                    id = s.optString("id"),
                    title = title,
                    updatedAt = s.optString("updated_at", ""),
                    busy = s.optBoolean("busy", false),
                    workspaceId = s.optString("workspace_id", "")
                )
            )
        }
        return out
    }

    fun listWorkspaces(server: String, token: String): List<WorkspaceItem> {
        val data = getData(server, token, "/api/v1/workspaces")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<WorkspaceItem>()
        for (i in 0 until items.length()) {
            val w = items.getJSONObject(i)
            out.add(
                WorkspaceItem(
                    id = w.optString("id"),
                    name = w.optString("name", "").ifEmpty { w.optString("root", "") },
                    root = w.optString("root", "")
                )
            )
        }
        return out
    }

    fun createSession(server: String, token: String, workspace: WorkspaceItem?): SessionItem {
        return createSessionAt(server, token, workspace?.root ?: Prefs.DEFAULT_WORKSPACE_ROOT, workspace?.id)
    }

    /** 指定 cwd 建会话（测试用，如 /tmp） */
    fun createSessionAt(server: String, token: String, cwd: String, workspaceId: String? = null): SessionItem {
        val payload = JSONObject()
            .put("metadata", JSONObject().put("cwd", cwd))
        if (workspaceId != null) payload.put("workspace_id", workspaceId)
        val data = postData(server, token, "/api/v1/sessions", payload)
        return SessionItem(
            id = data.getString("id"),
            title = data.optString("title", "").ifEmpty { "（新会话）" },
            updatedAt = data.optString("updated_at", ""),
            busy = false,
            workspaceId = data.optString("workspace_id", workspaceId ?: "")
        )
    }

    /** 会话模式档（对应 agent_config） */
    data class SessionProfile(
        val model: String,
        val thinking: String,
        val permissionMode: String, // manual / yolo / auto
        val planMode: Boolean,
        val swarmMode: Boolean,
        val goalObjective: String,
        val goalControl: String
    )

    private fun parseProfile(data: JSONObject): SessionProfile {
        val ac = data.optJSONObject("agent_config") ?: JSONObject()
        return SessionProfile(
            model = ac.optString("model", ""),
            thinking = ac.optString("thinking", ""),
            permissionMode = ac.optString("permission_mode", "manual"),
            planMode = ac.optBoolean("plan_mode", false),
            swarmMode = ac.optBoolean("swarm_mode", false),
            goalObjective = ac.optString("goal_objective", ""),
            goalControl = ac.optString("goal_control", "")
        )
    }

    fun getProfile(server: String, token: String, sessionId: String): SessionProfile {
        val data = getData(server, token, "/api/v1/sessions/$sessionId/profile")
        return parseProfile(data)
    }

    /** patch 例：JSONObject().put("plan_mode", true)。注意 v0.35.0 服务端不回显，仅确认接受 */
    fun updateProfile(server: String, token: String, sessionId: String, patch: JSONObject) {
        AppLog.log("HTTP", "profile patch: $patch")
        postData(server, token, "/api/v1/sessions/$sessionId/profile", JSONObject().put("agent_config", patch))
    }

    fun getMessages(server: String, token: String, sessionId: String): List<HistoryMessage> {
        val data = getData(server, token, "/api/v1/sessions/$sessionId/messages?page_size=100")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<HistoryMessage>()
        for (i in 0 until items.length()) {
            val m = items.getJSONObject(i)
            val role = m.optString("role", "")
            if (role != "user" && role != "assistant") continue
            val content = m.optJSONArray("content") ?: continue
            val sb = StringBuilder()
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == "text") {
                    val text = block.optString("text", "")
                    // 幻影消息：系统注入的 user 文本块整条隐藏
                    if (isPhantomUserText(role, text)) continue
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
            if (sb.isNotEmpty()) {
                out.add(HistoryMessage(m.optString("id"), role, sb.toString(), m.optString("created_at", "")))
            }
        }
        // API 返回最新在前，反转为时间正序（最旧在上）再渲染
        return out.asReversed()
    }

    /** prompt 队列快照：queued=排队中；active=当前正在执行的 prompt（实测 v0.37.2 起 active 不在 queued[] 里） */
    data class QueuedPrompt(val text: String, val createdAt: String)
    data class PromptQueue(val queued: List<QueuedPrompt>, val active: QueuedPrompt?)

    /**
     * 服务端排队/执行中的 prompt 快照（会话 busy 时 POST /prompts 排队，不进 GET /messages 历史）。
     * queued 取 data.queued[] 每条 prompt 的 text + created_at；active 取 data.active（当前执行中的 prompt）。
     * 多 text 块以换行拼接，与 getMessages 口径一致。
     */
    fun listQueuedPrompts(server: String, token: String, sessionId: String): PromptQueue {
        val data = getData(server, token, "/api/v1/sessions/$sessionId/prompts?status=queued")
        val items = data.optJSONArray("queued") ?: JSONArray()
        val out = ArrayList<QueuedPrompt>()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i)
            val text = promptText(p)
            if (text.isNotEmpty()) out.add(QueuedPrompt(text, p?.optString("created_at", "") ?: ""))
        }
        val activeObj = data.optJSONObject("active")
        val activeText = promptText(activeObj)
        val active = if (activeText.isEmpty()) null
            else QueuedPrompt(activeText, activeObj?.optString("created_at", "") ?: "")
        return PromptQueue(out, active)
    }

    /** 提取单条 prompt 的 text 内容（多 text 块以换行拼接；幻影系统注入文本剔除） */
    private fun promptText(p: JSONObject?): String {
        val content = p?.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (isPhantomUserText("user", text)) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString()
    }

    /** 返回 data.status：running / queued（会话 busy 时消息排队，不立即进入历史） */
    fun sendPrompt(server: String, token: String, sessionId: String, text: String, model: String, modes: SessionProfile? = null): String {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )
        val payload = JSONObject()
            .put("content", content)
            // 服务端要求顶层必须带 model，否则报 Model not set；模式栏有会话级模型时优先
            .put("model", modes?.model?.ifEmpty { null } ?: model)
        if (modes != null) {
            // 与官方 Web UI 一致：模式随每条 prompt 下发（/profile 的 GET 在 v0.35.0 不回显）
            payload.put("plan_mode", modes.planMode)
            payload.put("swarm_mode", modes.swarmMode)
            payload.put("permission_mode", modes.permissionMode)
        }
        val data = postData(server, token, "/api/v1/sessions/$sessionId/prompts", payload)
        return data.optString("status", "running")
    }

    /** 中断当前会话正在运行的 turn（实测返回 {"aborted":true}） */
    fun abortSession(server: String, token: String, sessionId: String) {
        postData(server, token, "/api/v1/sessions/$sessionId:abort", JSONObject())
    }

    /** 压缩会话历史（空历史时服务端报业务错误，如实测 40910） */
    fun compactSession(server: String, token: String, sessionId: String) {
        postData(server, token, "/api/v1/sessions/$sessionId:compact", JSONObject())
    }

    /** 归档会话：从活跃会话列表移除 */
    fun archiveSession(server: String, token: String, sessionId: String) {
        postData(server, token, "/api/v1/sessions/$sessionId:archive", JSONObject())
    }

    /** 分叉会话：以当前会话为起点复制出新会话，返回新会话 id（data 为新会话对象或含 id 字段） */
    fun forkSession(server: String, token: String, sessionId: String): String {
        val data = postData(server, token, "/api/v1/sessions/$sessionId:fork", JSONObject())
        return data.optJSONObject("session")?.optString("id") ?: data.optString("id", "")
    }

    /** 会话改名：POST /sessions/{id}/profile，body 顶层 title 字段（服务端实测） */
    fun renameSession(server: String, token: String, sessionId: String, title: String) {
        postData(server, token, "/api/v1/sessions/$sessionId/profile", JSONObject().put("title", title))
    }

    /**
     * 会话上下文使用量兜底（WS reset 快照/meta.merge 未上报时用）：
     * GET /sessions/{id} 的 usage.context_tokens/context_limit（实测可能全 0，全 0 视为无数据返回 null）。
     * 返回 contextTokens to maxContextTokens。
     */
    fun getSessionUsage(server: String, token: String, sessionId: String): Pair<Long, Long>? {
        val data = getData(server, token, "/api/v1/sessions/$sessionId")
        val usage = data.optJSONObject("usage") ?: return null
        val ctx = usage.optLong("context_tokens", -1)
        val limit = usage.optLong("context_limit", -1)
        if (ctx <= 0 && limit <= 0) return null
        return ctx to limit
    }

    /** 待审批的工具调用（GET .../approvals?status=pending） */
    data class ApprovalItem(
        val id: String,
        val toolName: String,
        val action: String,
        val summary: String
    )

    fun listPendingApprovals(server: String, token: String, sessionId: String): List<ApprovalItem> {
        val data = getData(server, token, "/api/v1/sessions/$sessionId/approvals?status=pending")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<ApprovalItem>()
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            val display = a.optJSONObject("tool_input_display")
            out.add(
                ApprovalItem(
                    id = a.optString("approval_id"),
                    toolName = a.optString("tool_name", ""),
                    action = a.optString("action", ""),
                    summary = display?.optString("summary", "") ?: ""
                )
            )
        }
        return out
    }

    /** 审批应答：decision=approved/rejected */
    fun respondApproval(server: String, token: String, sessionId: String, approvalId: String, approved: Boolean) {
        postData(
            server, token,
            "/api/v1/sessions/$sessionId/approvals/$approvalId",
            JSONObject().put("decision", if (approved) "approved" else "rejected")
        )
    }

    /** 待回答的问题（GET .../questions?status=pending）；协议与 Android 一致 */
    data class QuestionOption(val id: String, val label: String, val description: String)
    data class SubQuestion(
        val id: String,
        val question: String,
        val header: String,
        val options: List<QuestionOption>,
        val allowOther: Boolean
    )
    data class PendingQuestion(val id: String, val questions: List<SubQuestion>)

    fun listPendingQuestions(server: String, token: String, sessionId: String): List<PendingQuestion> {
        val data = getData(server, token, "/api/v1/sessions/$sessionId/questions?status=pending")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<PendingQuestion>()
        for (i in 0 until items.length()) {
            val q = items.optJSONObject(i) ?: continue
            val qs = q.optJSONArray("questions") ?: continue
            val subs = ArrayList<SubQuestion>()
            for (j in 0 until qs.length()) {
                val sq = qs.optJSONObject(j) ?: continue
                val opts = ArrayList<QuestionOption>()
                val optArr = sq.optJSONArray("options")
                if (optArr != null) {
                    for (k in 0 until optArr.length()) {
                        val o = optArr.optJSONObject(k) ?: continue
                        opts.add(
                            QuestionOption(
                                id = o.optString("id"),
                                label = o.optString("label", o.optString("text", o.optString("id"))),
                                description = o.optString("description", "")
                            )
                        )
                    }
                }
                subs.add(
                    SubQuestion(
                        id = sq.optString("id"),
                        question = sq.optString("question", ""),
                        header = sq.optString("header", ""),
                        options = opts,
                        allowOther = sq.optBoolean("allow_other", false)
                    )
                )
            }
            out.add(PendingQuestion(id = q.optString("question_id"), questions = subs))
        }
        return out
    }

    /**
     * 问题应答：answers 是 问题id → 答案对象 的映射。
     * kind=single 带 option_id；kind=other 带 text；kind=skipped 无额外字段（服务端 zod schema 还支持 multi/multi_with_other，本端问答 UI 仅单选，暂不产生）。
     */
    fun answerQuestion(server: String, token: String, sessionId: String, questionId: String, answers: JSONObject) {
        postData(
            server, token,
            "/api/v1/sessions/$sessionId/questions/$questionId",
            JSONObject().put("answers", answers)
        )
    }
}

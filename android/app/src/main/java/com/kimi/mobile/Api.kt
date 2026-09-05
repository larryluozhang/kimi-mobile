package com.kimi.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
    val root: String,
    val sessionCount: Int = 0
)

data class HistoryMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: String = ""
)

/** 一页历史消息：过滤后的可见消息 + 分页元信息。
 *  oldestRawId 是本页**原始**（含 tool 等被过滤角色）最旧一条的 id，用作 before_id 翻页锚点；
 *  hasMore 用原始 items 数是否达到 pageSize 粗判（服务端不回 total） */
data class MessagesPage(
    val items: List<HistoryMessage>,
    val oldestRawId: String?,
    val rawCount: Int,
    val hasMore: Boolean
)

/** 服务端队列中的单条 prompt：文本 + 入队时间（created_at，ISO 格式，用于消息排序） */
data class QueuedPrompt(
    val text: String,
    val createdAt: String = ""
)

/** 服务端 prompt 队列状态：queued 为排队中的 prompt，active 为当前正在执行的 prompt（不在 queued 里） */
data class PromptQueue(
    val queued: List<QueuedPrompt>,
    val active: QueuedPrompt?
)

data class ApprovalItem(
    val id: String,
    val toolName: String,
    val action: String,
    val summary: String,
    val createdAt: String
)

/** 问答单个选项 */
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String
)

/** 问答单题：id/question/header/options/allow_other（pending_interaction="question" 场景） */
data class QuestionEntry(
    val id: String,
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val allowOther: Boolean
)

/** 一次待回答的问答请求：question_id + 题目列表 */
data class QuestionItem(
    val questionId: String,
    val questions: List<QuestionEntry>
)

data class SessionProfile(
    val planMode: Boolean,
    val swarmMode: Boolean,
    val permissionMode: String, // "manual" / "auto" / "yolo"
    val model: String,
    val thinking: String,
    val goalObjective: String
)

object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 历史消息每页条数（原始消息，含 tool 角色） */
    const val MESSAGES_PAGE_SIZE = 100

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun builder(server: String, token: String, path: String): Request.Builder =
        Request.Builder()
            .url("$server$path")
            .header("Authorization", "Bearer $token")

    /** 返回 HTTP code；200 以外由调用方处理 */
    fun healthz(server: String): Int {
        return try {
            val c = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            c.newCall(Request.Builder().url("$server/api/v1/healthz").build()).execute().use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    private fun checkAuth(code: Int, body: String): JSONObject {
        if (code == 401 || code == 403) throw ApiException(code, "Token 无效或已过期（HTTP $code）")
        if (code != 200) throw ApiException(code, "HTTP $code: ${body.take(200)}")
        val obj = JSONObject(body)
        val code0 = obj.optInt("code", 0)
        if (code0 != 0) throw ApiException(200, obj.optString("msg", "服务器返回错误 code=$code0"))
        return obj.optJSONObject("data") ?: JSONObject()
    }

    fun listSessions(server: String, token: String): List<SessionItem> {
        // 不带 busy 过滤：busy=true（运行中/待审批）的会话也必须可见
        val req = builder(server, token, "/api/v1/sessions?page_size=50&include_archive=false&exclude_empty=false&archived_only=false").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
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
    }

    fun listWorkspaces(server: String, token: String): List<WorkspaceItem> {
        val req = builder(server, token, "/api/v1/workspaces").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val items = data.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<WorkspaceItem>()
            for (i in 0 until items.length()) {
                val w = items.getJSONObject(i)
                out.add(
                    WorkspaceItem(
                        id = w.optString("id"),
                        name = w.optString("name", "").ifEmpty { w.optString("root", "") },
                        root = w.optString("root", ""),
                        sessionCount = w.optInt("session_count", 0)
                    )
                )
            }
            return out
        }
    }

    fun createSession(server: String, token: String, workspace: WorkspaceItem): SessionItem {
        val payload = JSONObject()
            .put("metadata", JSONObject().put("cwd", workspace.root))
            .put("workspace_id", workspace.id)
        val req = builder(server, token, "/api/v1/sessions")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            return SessionItem(
                id = data.getString("id"),
                title = data.optString("title", "").ifEmpty { "（新会话）" },
                updatedAt = data.optString("updated_at", ""),
                busy = false,
                workspaceId = data.optString("workspace_id", workspace.id)
            )
        }
    }

    /** 拉一页历史消息；beforeId 非空时向前翻页（before_id 实测有效，传本页最旧一条**原始**消息的 id） */
    fun getMessages(server: String, token: String, sessionId: String, beforeId: String? = null): MessagesPage {
        var url = "/api/v1/sessions/$sessionId/messages?page_size=$MESSAGES_PAGE_SIZE"
        if (beforeId != null) url += "&before_id=" + URLEncoder.encode(beforeId, "UTF-8")
        val req = builder(server, token, url).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val items = data.optJSONArray("items") ?: JSONArray()
            // API 返回最新在前，最旧一条原始消息在末尾，作为下一次 before_id 的锚点
            val oldestRawId = if (items.length() > 0) items.getJSONObject(items.length() - 1).optString("id") else null
            val out = ArrayList<HistoryMessage>()
            for (i in 0 until items.length()) {
                val m = items.getJSONObject(i)
                val role = m.optString("role", "")
                if (role != "user" && role != "assistant") continue
                val content = m.optJSONArray("content") ?: continue
                val sb = StringBuilder()
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    // 只渲染 text 块；thinking 等其余块不进入正文
                    if (block.optString("type") == "text") {
                        val text = block.optString("text", "")
                        // 隐藏系统注入的用户消息块（日期提醒、cron 信封等），与官方 web UI 一致
                        if (role == "user" && isSystemInjected(text)) continue
                        if (text.isEmpty()) continue
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(text)
                    }
                }
                // 所有块都被过滤的消息整条不显示
                if (sb.isNotEmpty()) {
                    out.add(HistoryMessage(m.optString("id"), role, sb.toString(), m.optString("created_at", "")))
                }
            }
            // API 返回最新在前，反转为时间正序（最旧在上）再渲染
            return MessagesPage(
                items = out.asReversed(),
                oldestRawId = oldestRawId,
                rawCount = items.length(),
                hasMore = items.length() >= MESSAGES_PAGE_SIZE
            )
        }
    }

    /** 系统注入内容特征：以 <system-reminder>、<cron-fire 或 <notification 开头（允许前导空白） */
    private fun isSystemInjected(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<system-reminder>") || t.startsWith("<cron-fire") || t.startsWith("<notification")
    }

    /** 读取会话 profile（agent_config） */
    fun getProfile(server: String, token: String, sessionId: String): SessionProfile {
        val req = builder(server, token, "/api/v1/sessions/$sessionId/profile").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val ac = data.optJSONObject("agent_config") ?: JSONObject()
            return SessionProfile(
                planMode = ac.optBoolean("plan_mode", false),
                swarmMode = ac.optBoolean("swarm_mode", false),
                permissionMode = ac.optString("permission_mode", "manual"),
                model = ac.optString("model", ""),
                thinking = ac.optString("thinking", ""),
                goalObjective = ac.optString("goal_objective", "")
            )
        }
    }

    /** 更新会话 agent_config（只传需要修改的字段），失败抛 ApiException。
     *  注意 v0.35.0 服务端不回显（GET 恒返回 model:""），仅确认接受；
     *  真实状态由客户端本地持久化为准（Prefs.sessionMode）。 */
    fun updateProfile(server: String, token: String, sessionId: String, agentConfig: JSONObject) {
        val payload = JSONObject().put("agent_config", agentConfig)
        val req = builder(server, token, "/api/v1/sessions/$sessionId/profile")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            checkAuth(resp.code, body)
        }
    }

    /** 发送 prompt，返回服务端 status（"running" / "queued"；busy 时服务端排队，消息暂不进入历史） */
    fun sendPrompt(server: String, token: String, sessionId: String, text: String, model: String, modes: SessionProfile? = null): String {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )
        val payload = JSONObject()
            .put("content", content)
            // 模式栏有会话级模型时优先；否则用全局设置
            .put("model", modes?.model?.ifEmpty { null } ?: model)
        if (modes != null) {
            // 与官方 Web UI / macOS 版一致：模式随每条 prompt 顶层下发
            // （v0.35.0 的 GET /profile 不回显 agent_config）
            payload.put("plan_mode", modes.planMode)
            payload.put("swarm_mode", modes.swarmMode)
            payload.put("permission_mode", modes.permissionMode)
        }
        val req = builder(server, token, "/api/v1/sessions/$sessionId/prompts")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            return data.optString("status", "running")
        }
    }

    /** 会话动作：POST /api/v1/sessions/{id}:{action}（compact/archive/fork/abort），返回 data；
     *  业务错误（如空历史 compact 的 40910）抛 ApiException */
    fun sessionAction(server: String, token: String, sessionId: String, action: String): JSONObject {
        val req = builder(server, token, "/api/v1/sessions/$sessionId:$action")
            .post("{}".toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            return checkAuth(resp.code, body)
        }
    }

    /** 裁剪会话：POST /sessions/{id}:undo {"count":N}，从末尾移除 N 条 user 消息及其后内容（实测有效；40911=无可 undo） */
    fun undoSession(server: String, token: String, sessionId: String, count: Int) {
        val payload = JSONObject().put("count", count)
        val req = builder(server, token, "/api/v1/sessions/$sessionId:undo")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            checkAuth(resp.code, body)
        }
    }

    /** 会话详情兜底取上下文用量：usage.context_tokens / context_limit（实测该字段可能全 0，仅作兜底） */
    fun getSessionUsage(server: String, token: String, sessionId: String): Pair<Long, Long> {
        val req = builder(server, token, "/api/v1/sessions/$sessionId").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val usage = data.optJSONObject("usage") ?: return 0L to 0L
            return usage.optLong("context_tokens", 0) to usage.optLong("context_limit", 0)
        }
    }

    /** 重命名会话：POST /sessions/{id}/profile，body 为顶层 title 字段（不是 metadata.title，已实测） */
    fun renameSession(server: String, token: String, sessionId: String, title: String) {
        val payload = JSONObject().put("title", title)
        val req = builder(server, token, "/api/v1/sessions/$sessionId/profile")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            checkAuth(resp.code, body)
        }
    }

    /** 拉取服务端 prompt 队列状态（busy 时 POST 返回 queued，消息暂不进历史）。
     *  以服务端队列为真相来源，用于重进会话/重启后恢复“排队中”气泡；
     *  data.active 是当前正在执行的 prompt（v0.37.2，不在 queued[] 里）。异常向上抛，由调用方降级。 */
    fun listQueuedPrompts(server: String, token: String, sessionId: String): PromptQueue {
        val req = builder(server, token, "/api/v1/sessions/$sessionId/prompts?status=queued").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val queued = data.optJSONArray("queued") ?: JSONArray()
            val out = ArrayList<QueuedPrompt>()
            for (i in 0 until queued.length()) {
                val q = queued.optJSONObject(i)
                val text = promptText(q)
                if (text.isNotEmpty()) out.add(QueuedPrompt(text, q?.optString("created_at", "") ?: ""))
            }
            val activeObj = if (data.isNull("active")) null else data.optJSONObject("active")
            val active = activeObj?.let { promptText(it) }?.takeIf { it.isNotEmpty() }
                ?.let { QueuedPrompt(it, activeObj.optString("created_at", "")) }
            return PromptQueue(out, active)
        }
    }

    /** 从 prompt 对象的 content 数组提取全部 text 块（换行连接） */
    private fun promptText(q: JSONObject?): String {
        if (q == null) return ""
        val content = q.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString()
    }

    /** 轮询待审批项（status=pending），异常向上抛 */
    fun listPendingApprovals(server: String, token: String, sessionId: String): List<ApprovalItem> {
        val req = builder(server, token, "/api/v1/sessions/$sessionId/approvals?status=pending").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val items = data.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<ApprovalItem>()
            for (i in 0 until items.length()) {
                val a = items.getJSONObject(i)
                val display = a.optJSONObject("tool_input_display")
                out.add(
                    ApprovalItem(
                        id = a.optString("approval_id"),
                        toolName = a.optString("tool_name", ""),
                        action = a.optString("action", ""),
                        summary = display?.optString("summary", "") ?: "",
                        createdAt = a.optString("created_at", "")
                    )
                )
            }
            return out
        }
    }

    /** 响应审批：decision 为 approved / rejected */
    fun respondApproval(server: String, token: String, sessionId: String, approvalId: String, decision: String) {
        val payload = JSONObject().put("decision", decision)
        val req = builder(server, token, "/api/v1/sessions/$sessionId/approvals/$approvalId")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            checkAuth(resp.code, body)
        }
    }

    /** 轮询待回答问答（status=pending，pending_interaction="question" 场景），异常向上抛 */
    fun listPendingQuestions(server: String, token: String, sessionId: String): List<QuestionItem> {
        val req = builder(server, token, "/api/v1/sessions/$sessionId/questions?status=pending").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val data = checkAuth(resp.code, body)
            val items = data.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<QuestionItem>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val qs = item.optJSONArray("questions") ?: JSONArray()
                val entries = ArrayList<QuestionEntry>()
                for (j in 0 until qs.length()) {
                    val q = qs.optJSONObject(j) ?: continue
                    val opts = q.optJSONArray("options") ?: JSONArray()
                    val options = ArrayList<QuestionOption>()
                    for (k in 0 until opts.length()) {
                        val o = opts.optJSONObject(k) ?: continue
                        options.add(
                            QuestionOption(
                                id = o.optString("id"),
                                label = o.optString("label", ""),
                                description = o.optString("description", "")
                            )
                        )
                    }
                    entries.add(
                        QuestionEntry(
                            id = q.optString("id"),
                            question = q.optString("question", ""),
                            header = q.optString("header", ""),
                            options = options,
                            allowOther = q.optBoolean("allow_other", false)
                        )
                    )
                }
                if (entries.isNotEmpty()) {
                    out.add(QuestionItem(item.optString("question_id"), entries))
                }
            }
            return out
        }
    }

    /** 提交问答回答：answers 形如 {"<问题id>":{"kind":"single","option_id":"..."} / {"kind":"other","text":"..."} / {"kind":"skipped"} */
    fun respondQuestion(server: String, token: String, sessionId: String, questionId: String, answers: JSONObject) {
        val payload = JSONObject().put("answers", answers)
        val req = builder(server, token, "/api/v1/sessions/$sessionId/questions/$questionId")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            checkAuth(resp.code, body)
        }
    }
}

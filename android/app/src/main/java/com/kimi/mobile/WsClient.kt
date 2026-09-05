package com.kimi.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Kimi Code daemon WebSocket 客户端。
 *
 * 协议（实测 v0.35.0）：
 *  - 握手带 Authorization: Bearer <token>
 *  - 服务端先发 server_hello；客户端回 client_hello；收到 ack 后发 subscribe_v2
 *  - subscribe_v2 的 transcript 为 {agentId: grade}，"*" 通配 + "delta" 级别
 *  - 流式内容通过 volatile 的 transcript.ops 帧下发（frame.upsert / append / meta.merge / turn.upsert …）
 *  - 服务端每 10s 发 JSON ping，必须回 pong（同样 nonce）
 */
class WsClient(
    private val serverHttp: String,
    private val token: String,
    private val sessionId: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onClosed()
        fun onAuthError()
        fun onError(message: String)
        /** busy 状态变化（会话级别） */
        fun onWorkChanged(busy: Boolean)
        /** agent phase：running / streaming / ended / interrupted；stream 可能为 thinking / text */
        fun onPhase(kind: String, stream: String)
        /** 新的内容帧；kind 常见为 text / thinking；text 帧的 role 为 assistant */
        fun onFrameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String)
        /** 工具调用帧；state 为 running / done，summary 为截断后的展示摘要 */
        fun onToolFrame(frameId: String, name: String, state: String, summary: String)
        /** 向已有帧追加文本 */
        fun onFrameAppend(frameId: String, offset: Long, text: String)
        /** turn 状态：running / completed / failed / cancelled */
        fun onTurnState(state: String, error: String?)
        /** transcript 被重置（重连/换订阅），应丢弃本地流式缓冲 */
        fun onTranscriptReset()
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // 用心跳由服务端 JSON ping 承担
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var reconnectDelayMs = 1000L
    private var reconnectTask: ScheduledFuture<*>? = null
    private var helloId: String? = null
    private var subId: String? = null

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnectTask?.cancel(false)
        ws?.close(1000, "bye")
        ws = null
    }

    private fun wsUrl(): String {
        var u = serverHttp.trim().trimEnd('/')
        u = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            else -> "ws://$u"
        }
        return "$u/api/v1/ws"
    }

    private fun connect() {
        if (stopped) return
        val req = Request.Builder()
            .url(wsUrl())
            .header("Authorization", "Bearer $token")
            .build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                reconnectDelayMs = 1000L
                helloId = "h-" + UUID.randomUUID().toString()
                send(
                    JSONObject()
                        .put("type", "client_hello")
                        .put("id", helloId)
                        .put("payload", JSONObject().put("client_id", "kimi-mobile"))
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) {
                    stopped = true
                    listener.onAuthError()
                    return
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        listener.onClosed()
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30000L)
        reconnectTask = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    private fun handle(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (msg.optString("type")) {
            "ping" -> {
                val nonce = msg.optJSONObject("payload")?.optString("nonce") ?: return
                send(JSONObject().put("type", "pong").put("payload", JSONObject().put("nonce", nonce)))
            }
            "ack" -> {
                val id = msg.optString("id")
                if (msg.optInt("code", 0) != 0) {
                    listener.onError(msg.optString("msg", "服务器拒绝请求"))
                    return
                }
                if (id == helloId) {
                    subId = "s-" + UUID.randomUUID().toString()
                    send(
                        JSONObject()
                            .put("type", "subscribe_v2")
                            .put("id", subId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("session_id", sessionId)
                                    .put("transcript", JSONObject().put("*", "delta"))
                            )
                    )
                } else if (id == subId) {
                    listener.onOpen()
                }
            }
            "error" -> {
                val p = msg.optJSONObject("payload")
                val errMsg = p?.optString("msg") ?: "WebSocket 错误"
                if (p?.optBoolean("fatal", false) == true) {
                    listener.onError(errMsg)
                }
            }
            "event.session.work_changed" -> {
                val p = msg.optJSONObject("payload") ?: return
                listener.onWorkChanged(p.optBoolean("busy", false))
            }
            "transcript.reset" -> {
                listener.onTranscriptReset()
                // v0.37.2：turn 进行中才订阅的客户端收不到该 turn 的 transcript.ops，
                // 但 reset 快照的 meta.agent.phase 带实时阶段 → 据此立即反映工作状态。
                // 多 agent 会话会收到多个 reset，只取 main agent（agent_id=="main" 或带 meta 的）
                val p = msg.optJSONObject("payload") ?: return
                val meta = p.optJSONObject("snapshot")?.optJSONObject("meta")
                if (p.optString("agent_id") == "main" || meta != null) {
                    val phase = meta?.optJSONObject("agent")?.optJSONObject("phase")
                    if (phase != null) {
                        listener.onPhase(
                            kind = phase.optString("kind", ""),
                            stream = phase.optString("stream", "")
                        )
                    }
                }
            }
            "transcript.ops" -> {
                val p = msg.optJSONObject("payload") ?: return
                val ops = p.optJSONArray("ops") ?: return
                dispatchOps(ops)
            }
        }
    }

    private fun dispatchOps(ops: JSONArray) {
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "frame.upsert" -> {
                    val frame = op.optJSONObject("frame") ?: continue
                    val kind = frame.optString("kind", "")
                    if (kind == "tool") {
                        // 工具调用帧：display.summary ?: inputText ?: input，截断到 80 字符
                        val rawSummary = frame.optJSONObject("display")?.optString("summary", "")?.takeIf { it.isNotEmpty() }
                            ?: frame.optString("inputText", "").takeIf { it.isNotEmpty() }
                            ?: frame.optJSONObject("input")?.toString()
                            ?: ""
                        listener.onToolFrame(
                            frameId = frame.optString("frameId", ""),
                            name = frame.optString("name", ""),
                            state = frame.optString("state", ""),
                            summary = rawSummary.replace('\n', ' ').take(80)
                        )
                        continue
                    }
                    listener.onFrameUpsert(
                        turnId = op.optString("turnId", ""),
                        frameId = frame.optString("frameId", ""),
                        kind = kind,
                        role = frame.optString("role", ""),
                        text = frame.optString("text", "")
                    )
                }
                "append" -> {
                    val target = op.optJSONObject("target") ?: continue
                    if (target.optString("type") != "frame") continue
                    listener.onFrameAppend(
                        frameId = target.optString("frameId", ""),
                        offset = op.optLong("offset", 0),
                        text = op.optString("text", "")
                    )
                }
                "meta.merge" -> {
                    val phase = op.optJSONObject("meta")
                        ?.optJSONObject("agent")
                        ?.optJSONObject("phase") ?: continue
                    listener.onPhase(
                        kind = phase.optString("kind", ""),
                        stream = phase.optString("stream", "")
                    )
                }
                "turn.upsert" -> {
                    val turn = op.optJSONObject("turn") ?: continue
                    val state = turn.optString("state", "")
                    val err = if (turn.isNull("error")) null else turn.getString("error")
                    listener.onTurnState(state, err)
                }
            }
        }
    }
}

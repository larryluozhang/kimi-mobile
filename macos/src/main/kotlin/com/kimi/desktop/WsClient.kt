package com.kimi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Kimi Code daemon WebSocket 客户端（与 Android 版 WsClient.kt 协议一致，
 * 传输层从 OkHttp 换成本机可用的阻塞式 NIO SocketChannel + 手写 WS 帧编解码，
 * 原因见 MiniHttp 的注释）。
 *
 * 协议（实测 v0.35.0）：
 *  - 握手带 Authorization: Bearer <token>
 *  - 客户端发 client_hello；收到 ack 后发 subscribe_v2（transcript = {"*": "delta"}）
 *  - 流式内容通过 transcript.ops 帧下发（frame.upsert / append / meta.merge / turn.upsert …）
 *  - 服务端每 10s 发 JSON ping，必须回 pong（同样 nonce）
 *  - 注意：实测 v0.37.2 在非 loopback（如 Tailscale IP）连接上不发心跳 ping，
 *    因此不能依赖被动收包判活：空闲 15s 主动发协议级 ping(0x9) 探活，
 *    超过 35s 仍无任何帧才判定掉线重连
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
        fun onWorkChanged(busy: Boolean)
        fun onPhase(kind: String, stream: String)
        fun onFrameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String)
        fun onFrameAppend(frameId: String, offset: Long, text: String)
        /** 工具调用帧（frame.kind="tool"）；summary 已去换行并截断 */
        fun onToolFrame(turnId: String, frameId: String, name: String, state: String, summary: String)
        fun onTurnState(state: String, error: String?)
        fun onTranscriptReset()
        /** 上下文使用量（token）；maxContextTokens<=0 表示服务端未上报上限。默认空实现（E2e 等不关心） */
        fun onContextUsage(contextTokens: Long, maxContextTokens: Long) {}
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val random = SecureRandom()
    private val writeLock = Any()

    @Volatile private var channel: SocketChannel? = null
    @Volatile private var stopped = false
    @Volatile private var reconnectDelayMs = 1000L
    @Volatile private var lastRxAt = System.currentTimeMillis()
    private var reconnectTask: ScheduledFuture<*>? = null
    private var watchdogTask: ScheduledFuture<*>? = null
    private var ioThread: Thread? = null
    private var helloId: String? = null
    private var subId: String? = null

    @Volatile private var probePingSentAt = 0L

    fun start() {
        stopped = false
        watchdogTask = scheduler.scheduleWithFixedDelay({
            val ch = channel
            val idleMs = System.currentTimeMillis() - lastRxAt
            if (!stopped && ch != null) {
                if (idleMs > 35_000) {
                    runCatching { ch.close() } // 读线程抛错后走重连
                } else if (idleMs > 15_000 && System.currentTimeMillis() - probePingSentAt > 15_000) {
                    // 非 loopback 连接服务端不发心跳（实测 v0.37.2），主动发协议级 ping 探活
                    probePingSentAt = System.currentTimeMillis()
                    runCatching { sendFrame(0x9, ByteArray(0)) }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)
        connectAsync()
    }

    fun stop() {
        stopped = true
        reconnectTask?.cancel(false)
        watchdogTask?.cancel(false)
        runCatching { channel?.close() }
        channel = null
        scheduler.shutdown()
    }

    private fun wsUri(): URI {
        var u = serverHttp.trim().trimEnd('/')
        u = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            else -> "ws://$u"
        }
        return URI("$u/api/v1/ws")
    }

    private fun connectAsync() {
        ioThread = Thread({
            try {
                AppLog.log("WS", "连接 ${wsUri()}")
                connectAndRead()
            } catch (e: AuthFailure) {
                AppLog.log("WS", "认证失败(401/403)")
                stopped = true
                listener.onAuthError()
            } catch (e: Exception) {
                AppLog.error("WS", "连接/读取中断", e)
                if (!stopped) scheduleReconnect()
            }
        }, "kimi-ws-io")
        ioThread!!.isDaemon = true
        ioThread!!.start()
    }

    private class AuthFailure : Exception()

    private fun connectAndRead() {
        val uri = wsUri()
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 80
        val ch = SocketChannel.open()
        val connectKiller = scheduler.schedule({ runCatching { ch.close() } }, 10, TimeUnit.SECONDS)
        try {
            ch.connect(InetSocketAddress(host, port))
        } finally {
            connectKiller.cancel(false)
        }
        handshake(ch, host, port, uri.rawPath ?: "/api/v1/ws")
        AppLog.log("WS", "握手成功(101)")
        channel = ch
        reconnectDelayMs = 1000L
        lastRxAt = System.currentTimeMillis()

        helloId = "h-" + UUID.randomUUID().toString()
        sendJson(
            JSONObject()
                .put("type", "client_hello")
                .put("id", helloId)
                .put("payload", JSONObject().put("client_id", "kimi-mobile-desktop"))
        )
        readerLoop(ch)
    }

    /** HTTP Upgrade 握手；101 通过，401/403 抛 AuthFailure，其余抛 IOException */
    private fun handshake(ch: SocketChannel, host: String, port: Int, path: String) {
        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = StringBuilder()
            .append("GET $path HTTP/1.1\r\n")
            .append("Host: $host:$port\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: $key\r\n")
            .append("Sec-WebSocket-Version: 13\r\n")
            .append("Authorization: Bearer $token\r\n")
            .append("\r\n")
        MiniHttp.writeAll(ch, req.toString().toByteArray(StandardCharsets.US_ASCII))

        val headBytes = MiniHttp.readUntil(ch, "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        val head = String(headBytes, StandardCharsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val code = lines[0].split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("bad handshake status: ${lines[0]}")
        if (code == 401 || code == 403) throw AuthFailure()
        if (code != 101) throw IOException("handshake failed: HTTP $code")
        val expect = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
        )
        val accept = lines.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != null && accept != expect) throw IOException("bad Sec-WebSocket-Accept")
    }

    private fun scheduleReconnect() {
        if (stopped) return
        listener.onClosed()
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        reconnectTask = scheduler.schedule({ if (!stopped) connectAsync() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun sendJson(obj: JSONObject) {
        sendFrame(0x1, obj.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /** 客户端帧必须 mask */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val ch = channel ?: return
        synchronized(writeLock) {
            try {
                val header = java.io.ByteArrayOutputStream()
                header.write(0x80 or opcode)
                when {
                    payload.size < 126 -> header.write(0x80 or payload.size)
                    payload.size <= 0xFFFF -> {
                        header.write(0x80 or 126)
                        header.write((payload.size ushr 8) and 0xFF)
                        header.write(payload.size and 0xFF)
                    }
                    else -> {
                        header.write(0x80 or 127)
                        for (i in 7 downTo 0) header.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
                    }
                }
                val mask = ByteArray(4).also { random.nextBytes(it) }
                header.write(mask)
                MiniHttp.writeAll(ch, header.toByteArray())
                val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
                MiniHttp.writeAll(ch, masked)
            } catch (e: Exception) {
                runCatching { ch.close() } // 让读线程感知并重连
            }
        }
    }

    private fun readerLoop(ch: SocketChannel) {
        val textBuf = StringBuilder()
        var fragmented = false
        while (!stopped) {
            val b = MiniHttp.readN(ch, 2)
            val fin = b[0].toInt() and 0x80 != 0
            val opcode = b[0].toInt() and 0x0F
            val masked = b[1].toInt() and 0x80 != 0
            var len = (b[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val ext = MiniHttp.readN(ch, 2)
                len = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val ext = MiniHttp.readN(ch, 8)
                len = 0
                for (i in 0 until 8) len = (len shl 8) or (ext[i].toInt() and 0xFF).toLong()
            }
            val mask = if (masked) MiniHttp.readN(ch, 4) else null
            val payload = MiniHttp.readN(ch, len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            lastRxAt = System.currentTimeMillis()

            when (opcode) {
                0x8 -> { // close
                    runCatching { sendFrame(0x8, ByteArray(0)) }
                    throw EOFException("server closed")
                }
                0x9 -> sendFrame(0xA, payload) // ping -> pong
                0xA -> { } // pong
                0x1, 0x0 -> {
                    if (opcode == 0x1) { textBuf.setLength(0); fragmented = !fin }
                    textBuf.append(String(payload, StandardCharsets.UTF_8))
                    if (fin) {
                        fragmented = false
                        handle(textBuf.toString())
                        textBuf.setLength(0)
                    }
                }
                else -> { } // 二进制等忽略
            }
        }
    }

    private fun handle(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            AppLog.log("WS", "无法解析的消息: ${text.take(200)}")
            return
        }
        val type = msg.optString("type")
        when (type) {
            "ping" -> {
                val nonce = msg.optJSONObject("payload")?.optString("nonce") ?: return
                sendJson(JSONObject().put("type", "pong").put("payload", JSONObject().put("nonce", nonce)))
            }
            "ack" -> {
                val id = msg.optString("id")
                if (msg.optInt("code", 0) != 0) {
                    listener.onError(msg.optString("msg", "服务器拒绝请求"))
                    return
                }
                if (id == helloId) {
                    subId = "s-" + UUID.randomUUID().toString()
                    sendJson(
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
                    AppLog.log("WS", "订阅成功 session=$sessionId")
                    listener.onOpen()
                }
            }
            "transcript.ops" -> {
                val p = msg.optJSONObject("payload") ?: return
                val ops = p.optJSONArray("ops") ?: return
                val kinds = StringBuilder()
                for (i in 0 until ops.length()) {
                    if (i > 0) kinds.append(',')
                    kinds.append(ops.optJSONObject(i)?.optString("op") ?: "?")
                }
                AppLog.log("WS", "transcript.ops x${ops.length()}: $kinds")
                dispatchOps(ops)
            }
            "transcript.reset" -> {
                AppLog.log("WS", "transcript.reset")
                // 迟订阅收不到进行中 turn 的 transcript.ops（实测 v0.37.2），
                // 但 reset 快照 meta.agent.phase 带实时阶段（running/streaming/tool_call/ended…）。
                // 多 agent 会发多条 reset：只取 main agent（agent_id=="main"，或无 agent_id 且 meta 非空）
                val p = msg.optJSONObject("payload")
                if (p != null) {
                    val meta = p.optJSONObject("snapshot")?.optJSONObject("meta")
                    val agentId = p.optString("agent_id", "")
                    if (agentId == "main" || (agentId.isEmpty() && meta != null && meta.length() > 0)) {
                        val agent = meta?.optJSONObject("agent")
                        val phase = agent?.optJSONObject("phase")
                        if (phase != null) {
                            AppLog.log("WS", "reset 快照 phase=${phase.optString("kind", "")}")
                            listener.onPhase(
                                kind = phase.optString("kind", ""),
                                stream = phase.optString("stream", "")
                            )
                        }
                        // 同层取上下文使用量（contextTokens/maxContextTokens）
                        if (agent != null && agent.has("contextTokens")) {
                            listener.onContextUsage(
                                agent.optLong("contextTokens", -1),
                                agent.optLong("maxContextTokens", -1)
                            )
                        }
                    }
                }
                listener.onTranscriptReset()
            }
            "event.session.work_changed" -> {
                val p = msg.optJSONObject("payload") ?: return
                AppLog.log("WS", "work_changed busy=${p.optBoolean("busy", false)}")
                listener.onWorkChanged(p.optBoolean("busy", false))
            }
            "error" -> {
                val p = msg.optJSONObject("payload")
                val errMsg = p?.optString("msg") ?: "WebSocket 错误"
                AppLog.log("WS", "error: $errMsg fatal=${p?.optBoolean("fatal", false)}")
                if (p?.optBoolean("fatal", false) == true) {
                    listener.onError(errMsg)
                }
            }
            else -> AppLog.log("WS", "收到消息 type=$type len=${text.length}")
        }
    }

    private fun dispatchOps(ops: JSONArray) {
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "frame.upsert" -> {
                    val frame = op.optJSONObject("frame") ?: continue
                    if (frame.optString("kind", "") == "tool") {
                        // 工具帧：信息在 name/state/input/inputText/display 里，text 为空
                        val display = frame.optJSONObject("display")
                        var summary = display?.optString("summary", "") ?: ""
                        if (summary.isEmpty()) summary = frame.optString("inputText", "")
                        if (summary.isEmpty()) summary = frame.opt("input")?.toString() ?: ""
                        summary = summary.replace('\n', ' ').replace('\r', ' ').trim()
                        if (summary.length > 80) summary = summary.take(80) + "…"
                        listener.onToolFrame(
                            turnId = op.optString("turnId", ""),
                            frameId = frame.optString("frameId", ""),
                            name = frame.optString("name", ""),
                            state = frame.optString("state", ""),
                            summary = summary
                        )
                        continue
                    }
                    listener.onFrameUpsert(
                        turnId = op.optString("turnId", ""),
                        frameId = frame.optString("frameId", ""),
                        kind = frame.optString("kind", ""),
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
                    val agent = op.optJSONObject("meta")?.optJSONObject("agent") ?: continue
                    val phase = agent.optJSONObject("phase")
                    if (phase != null) {
                        listener.onPhase(
                            kind = phase.optString("kind", ""),
                            stream = phase.optString("stream", "")
                        )
                    }
                    // agent.contextTokens（可能无 phase 同发，二者独立处理）
                    if (agent.has("contextTokens")) {
                        listener.onContextUsage(
                            agent.optLong("contextTokens", -1),
                            agent.optLong("maxContextTokens", -1)
                        )
                    }
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

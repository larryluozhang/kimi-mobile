package com.kimi.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * 端到端链路自检：`--e2e` 启动时执行，无窗口。
 * 验证完整链路：healthz → 会话列表 → WS 订阅 → POST 发消息 → 流式帧 → turn 结束 → 历史落盘。
 * 全程写 app.log（标签 E2E/WS/HTTP），结束打印结论并退出。
 */
object E2e {

    fun run(idleSeconds: Int = 0): Nothing {
        AppLog.installCrashHandler()
        AppLog.log("E2E", "==== 链路自检开始 (idle=${idleSeconds}s) ====")
        val server = Prefs.serverUrl()
        val token = Prefs.token()
        var exitCode = 1
        try {
            val hz = Api.healthz(server)
            AppLog.log("E2E", "healthz=$hz")
            check(hz == 200) { "healthz 失败: $hz" }

            val sessions = Api.listSessions(server, token)
            check(sessions.isNotEmpty()) { "无会话" }
            val sid = sessions.first().id
            AppLog.log("E2E", "目标会话 $sid（${sessions.first().title}）")

            val historyBefore = Api.getMessages(server, token, sid).messages.size
            AppLog.log("E2E", "历史消息基线=$historyBefore")

            val opened = CountDownLatch(1)
            val done = CountDownLatch(1)
            val upserts = java.util.concurrent.atomic.AtomicInteger(0)
            val appends = java.util.concurrent.atomic.AtomicInteger(0)
            val assistantChars = java.util.concurrent.atomic.AtomicInteger(0)
            val busyEvents = java.util.concurrent.atomic.AtomicInteger(0)
            val turnState = java.util.concurrent.atomic.AtomicReference("")

            val ws = WsClient(server, token, sid, object : WsClient.Listener {
                override fun onOpen() { AppLog.log("E2E", "WS onOpen"); opened.countDown() }
                override fun onClosed() { AppLog.log("E2E", "WS onClosed") }
                override fun onAuthError() { AppLog.log("E2E", "WS onAuthError"); done.countDown() }
                override fun onError(message: String) { AppLog.log("E2E", "WS onError: $message") }
                override fun onWorkChanged(busy: Boolean) { busyEvents.incrementAndGet(); AppLog.log("E2E", "work_changed busy=$busy") }
                override fun onPhase(kind: String, stream: String) { AppLog.log("E2E", "phase $kind/$stream") }
                override fun onFrameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String) {
                    upserts.incrementAndGet()
                    if (kind == "text" && role != "user") assistantChars.addAndGet(text.length)
                    AppLog.log("E2E", "frame.upsert kind=$kind role=$role len=${text.length}")
                }
                override fun onFrameAppend(frameId: String, offset: Long, text: String) {
                    appends.incrementAndGet()
                    assistantChars.addAndGet(text.length)
                }
                override fun onToolFrame(turnId: String, frameId: String, name: String, state: String, summary: String) {
                    AppLog.log("E2E", "tool frame name=$name state=$state: ${summary.take(60)}")
                }
                override fun onTurnState(state: String, error: String?) {
                    turnState.set(state)
                    AppLog.log("E2E", "turn.upsert state=$state error=$error")
                    if (state == "completed" || state == "failed" || state == "cancelled") done.countDown()
                }
                override fun onTranscriptReset() { AppLog.log("E2E", "transcript.reset") }
            })
            ws.start()
            check(opened.await(15, TimeUnit.SECONDS)) { "WS 订阅超时" }

            if (idleSeconds > 0) {
                // 复现用户场景：订阅后空闲一段时间再发送
                AppLog.log("E2E", "订阅后空闲 ${idleSeconds}s …")
                Thread.sleep(idleSeconds * 1000L)
                AppLog.log("E2E", "空闲结束，开始发送")
            }

            val prompt = "链路自检：请只回复 OK 两个字，不要做别的"
            Api.sendPrompt(server, token, sid, prompt, Prefs.model())
            AppLog.log("E2E", "POST /prompts 已接受: $prompt")

            val finished = done.await(120, TimeUnit.SECONDS)
            AppLog.log("E2E", "turn 结束=$finished state=${turnState.get()} upserts=${upserts.get()} appends=${appends.get()} assistantChars=${assistantChars.get()} busyEvents=${busyEvents.get()}")

            Thread.sleep(1000)
            val historyAfter = Api.getMessages(server, token, sid).messages
            AppLog.log("E2E", "历史消息 after=${historyAfter.size}（基线 $historyBefore）")
            val last = historyAfter.takeLast(2).joinToString(" | ") { "${it.role}:${it.text.take(40)}" }
            AppLog.log("E2E", "末尾消息: $last")

            ws.stop()
            val ok = finished && upserts.get() > 0 && historyAfter.size > historyBefore
            AppLog.log("E2E", if (ok) "==== 自检通过 ====" else "==== 自检失败 ====")
            exitCode = if (ok) 0 else 2
        } catch (e: Throwable) {
            AppLog.error("E2E", "自检异常", e)
            exitCode = 3
        }
        AppLog.log("E2E", "exit=$exitCode")
        System.out.flush()
        exitProcess(exitCode)
    }

    /**
     * 会话模式档自检：/tmp 新会话，不碰用户会话。
     * 注意：服务端 v0.35.0 的 toWireSession 硬编码 agent_config={model:""}，GET/POST /profile 均不回显；
     * POST 对运行中 agent 是真实生效的（applyAgentConfig→plan.enter/swarm.enter/goal 等）。
     * 因此本自检验证「接受（code==0）」而非「回显」，回显由客户端本地持久化承担（同官方 Web UI）。
     */
    fun runProfileTest(): Nothing {
        AppLog.installCrashHandler()
        AppLog.log("E2E", "==== profile 自检开始 ====")
        val server = Prefs.serverUrl()
        val token = Prefs.token()
        var exitCode = 1
        try {
            check(Api.healthz(server) == 200) { "healthz 失败" }
            val session = Api.createSessionAt(server, token, "/tmp")
            val sid = session.id
            AppLog.log("E2E", "测试会话 $sid (cwd=/tmp)")

            val p0 = Api.getProfile(server, token, sid)
            AppLog.log("E2E", "GET 初始: plan=${p0.planMode} swarm=${p0.swarmMode} perm=${p0.permissionMode} model='${p0.model}'（v0.35.0 不回显，预期全默认）")

            // 各项 PATCH 均应被接受（code==0，无异常即通过）
            Api.updateProfile(server, token, sid, JSONObject().put("plan_mode", true).put("swarm_mode", true))
            AppLog.log("E2E", "PATCH plan+swarm 已接受")
            Api.updateProfile(server, token, sid, JSONObject().put("permission_mode", "yolo"))
            AppLog.log("E2E", "PATCH perm=yolo 已接受")
            Api.updateProfile(server, token, sid, JSONObject().put("model", "kimi-code/k3-256k"))
            AppLog.log("E2E", "PATCH model 已接受")
            Api.updateProfile(server, token, sid, JSONObject().put("goal_objective", "自检目标：仅验证 API，请忽略"))
            AppLog.log("E2E", "PATCH goal 创建 已接受")
            for (c in listOf("pause", "resume", "cancel")) {
                Api.updateProfile(server, token, sid, JSONObject().put("goal_control", c))
                AppLog.log("E2E", "PATCH goal $c 已接受")
            }

            // 带模式的 prompt 下发（官方 Web UI 的机制）
            Api.sendPrompt(server, token, sid, "自检：请只回复 OK", Prefs.model(),
                Api.SessionProfile("kimi-code/k3-256k", "", "yolo", true, true, "", ""))
            AppLog.log("E2E", "POST /prompts 带 plan/swarm/perm/model 已接受")

            // 本地持久化读写
            val prof = Api.SessionProfile("kimi-code/k3-256k", "", "yolo", true, true, "自检目标", "")
            Prefs.saveSessionMode(sid, prof)
            val back = Prefs.sessionMode(sid)
            check(back != null && back.planMode && back.swarmMode && back.permissionMode == "yolo" &&
                back.model == "kimi-code/k3-256k" && back.goalObjective == "自检目标") { "本地持久化读写不一致: $back" }
            AppLog.log("E2E", "本地持久化读写一致")

            AppLog.log("E2E", "==== profile 自检通过 ====")
            exitCode = 0
        } catch (e: Throwable) {
            AppLog.error("E2E", "profile 自检异常", e)
            exitCode = 3
        }
        AppLog.log("E2E", "exit=$exitCode")
        System.out.flush()
        exitProcess(exitCode)
    }
}

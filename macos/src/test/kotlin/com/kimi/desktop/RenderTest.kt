package com.kimi.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 离屏渲染冒烟测试：验证门控页与主界面（真实服务端数据）能正常渲染 */
class RenderTest {
    @get:Rule
    val compose = createComposeRule()

    init {
        // gradle.properties 里的 systemProp 代理会带进测试 JVM；桌面 app 运行时并没有这些属性，清掉
        listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach(System::clearProperty)
    }

    private fun save(img: BufferedImage, path: String) {
        ImageIO.write(img, "png", File(path))
        println("saved $path ${img.width}x${img.height}")
    }

    @Test
    fun gateScreenRenders() {
        val state = AppState()
        state.gateMessage = "无法连接服务器（测试文案）"
        compose.setContent { KimiTheme { GateScreen(state) } }
        compose.waitForIdle()
        save(compose.onRoot().captureToImage().toAwtImage(), "/tmp/kimi-render-gate.png")
    }

    @Test
    fun mainScreenRenders() {
        val state = AppState()
        state.screen = Screen.Main
        compose.setContent { KimiTheme { MainScreen(state) } }
        // 等工作区/会话从真实服务端加载完
        compose.waitUntil(timeoutMillis = 20_000) { !state.sessionsLoading }
        compose.waitForIdle()
        println("proxyHost=" + System.getProperty("http.proxyHost"))
        println("sidebarError=" + state.sidebarError)
        save(compose.onRoot().captureToImage().toAwtImage(), "/tmp/kimi-render-main.png")
        assert(state.workspaces.isNotEmpty()) { "工作区列表为空: ${state.sidebarError}" }
        assert(state.sessions.isNotEmpty()) { "会话列表为空: ${state.sidebarError}" }

        // 打开第一个会话：验证历史加载 + WS 订阅握手
        state.activeSessionId = state.sessions.first().id
        compose.waitUntil(timeoutMillis = 20_000) { !state.historyLoading }
        compose.waitUntil(timeoutMillis = 15_000) { state.wsConnected }
        compose.waitForIdle()
        println("messages=" + state.messages.size + " wsConnected=" + state.wsConnected + " chatError=" + state.chatError)
        save(compose.onRoot().captureToImage().toAwtImage(), "/tmp/kimi-render-chat.png")
        assert(state.messages.isNotEmpty()) { "会话历史为空" }
        assert(state.wsConnected) { "WebSocket 未连通: ${state.chatError}" }

        // 完整发送→流式渲染→turn 结束链路（走真实 MainScreen/ChatPane 代码路径）
        val before = state.messages.size
        Thread {
            try {
                Api.sendPrompt(state.server(), state.token(), state.activeSessionId, "链路自检：请只回复 OK 两个字，不要做别的", state.model)
            } catch (e: Throwable) {
                println("sendPrompt FAIL: $e")
            }
        }.start()
        // 等待服务器受理（work_changed/turn running 会把 busy 置 true）
        compose.waitUntil(timeoutMillis = 20_000) { state.busy }
        // 流式帧出现（frame.upsert/append 驱动）；短回合可能直接结束
        compose.waitUntil(timeoutMillis = 60_000) { state.frames.isNotEmpty() || !state.busy }
        compose.waitForIdle()
        println("streaming frames=" + state.frames.size + " busy=" + state.busy)
        save(compose.onRoot().captureToImage().toAwtImage(), "/tmp/kimi-render-streaming.png")
        // turn 结束：历史重拉、frames 清空
        compose.waitUntil(timeoutMillis = 90_000) { !state.busy && state.frames.isEmpty() }
        compose.waitForIdle()
        println("after: messages=" + state.messages.size + " last=" + state.messages.lastOrNull()?.role + ":" + state.messages.lastOrNull()?.text?.take(30))
        save(compose.onRoot().captureToImage().toAwtImage(), "/tmp/kimi-render-reply.png")
        assert(state.messages.size > before) { "turn 结束后历史未增长" }
        // 会话可能有其他客户端并发写入，只断言末尾几条里有非空助手回复
        assert(state.messages.takeLast(4).any { it.role == "assistant" && it.text.isNotEmpty() }) {
            "末尾无助手回复: " + state.messages.takeLast(3).joinToString(" | ") { "${it.role}:${it.text.take(30)}" }
        }
    }
}

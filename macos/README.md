# Kimi Mobile (macOS)

Kimi Code web 服务端的 macOS 桌面客户端（Compose Multiplatform Desktop）。功能与 Android 版对齐：Tailscale 门控、会话/工作区切换、WS 流式聊天、多主机档案、会话模式栏。

## 构建

需要 JDK（便携版在 `~/kimi-desktop-toolchain/jdk-21.0.12+8`）：

```bash
JAVA_HOME=~/kimi-desktop-toolchain/jdk-21.0.12+8/Contents/Home ./gradlew clean packageDmg
```

**注意**：Compose 插件的 createDistributable 增量缓存可能静默打旧 jar，发版务必 `clean` 并检查 DMG 内 jar 版本号。

## 重要：本机网络栈

本机（OCLP macOS 15）JVM 高层网络栈损坏：java.net.Socket/HttpClient/OkHttp 均"连得上读不到数据"，只有 NIO SocketChannel 正常。因此网络层是手写的 `MiniHttp.kt`（NIO HTTP）和 `WsClient.kt`（NIO WebSocket 帧编解码）——**不要换回 OkHttp**。

## 协议要点

同 Android 版 README。调试日志在 `~/.kimi-mobile/app.log`；`--e2e` / `--e2e-profile` 提供无窗口自检。

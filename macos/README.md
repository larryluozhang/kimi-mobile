# Kimi Mobile (macOS)

Kimi Code web 服务端的 macOS 桌面客户端（Compose Multiplatform Desktop）。功能与 Android 版对齐：门控页、会话/工作区切换、WS 流式聊天、多主机档案、会话模式栏、审批/问答卡片、版本显示。

## 构建

需要 JDK 17+：

```bash
./gradlew clean packageDmg
```

**注意**：Compose 插件的 createDistributable 增量缓存可能静默打旧 jar，发版务必 `clean` 并检查 DMG 内 jar 版本号。

## 说明：手写网络层

`MiniHttp.kt`（NIO HTTP）和 `WsClient.kt`（NIO WebSocket 帧编解码）是手写的——某些老机器上（如 OCLP macOS）JVM 高层网络栈（java.net.Socket/HttpClient/OkHttp）存在"连得上读不到数据"的问题，只有 NIO SocketChannel 正常。如果你的机器 JVM 网络正常，可以替换回 OkHttp，但不建议。

## 协议要点

见根目录 `docs/PROTOCOL-NOTES.md`。调试日志在 `~/.kimi-mobile/app.log`；`--e2e` / `--e2e-profile` 提供无窗口自检。

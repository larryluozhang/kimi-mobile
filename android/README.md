# Kimi Mobile — Android 客户端

Kimi Code 本地服务端（`kimi web`）的原生 Android 客户端。

## 构建

需要 Android SDK（API 34）与 JDK 17+：

```bash
./gradlew assembleRelease
```

签名发布包需在项目根目录创建 `keystore.properties`（**不入库**）：

```properties
storeFile=/绝对路径/你的.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

未配置时可直接用 `./gradlew assembleDebug` 出调试包。

## 功能

见仓库根目录 README。代码结构：

- `Api.kt` — REST 封装（会话/工作区/消息/profile/审批/问答/队列/中断）
- `WsClient.kt` — WebSocket 客户端（OkHttp，心跳/订阅/transcript.ops 解析）
- `ChatActivity.kt` — 聊天页：流式渲染、工具流水、审批/问答弹窗、排队/执行中/未送达调和
- `SessionsActivity.kt` — 会话列表（工作区切换、30s 自刷、运行中徽标）
- `Prefs.kt` — 主机档案与本地持久化（SharedPreferences）

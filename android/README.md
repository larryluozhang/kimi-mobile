# Kimi Mobile (Android)

Kimi Code web 服务端的原生安卓客户端。连接自建 kimi web（REST + WebSocket），支持流式聊天、语音输入、工作区切换、多主机档案、会话模式栏（计划/Swarm/权限/模型/目标）。

## 构建

构建机在 146（Linux）：SDK `/opt/kimi/android-sdk`，Gradle 8.9。

```bash
ANDROID_HOME=/opt/kimi/android-sdk ./gradlew assembleRelease
```

签名需要项目根目录的 `keystore.properties`（不入库）：

```properties
storeFile=/opt/kimi/android-sdk/keystore/kimi-mobile.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

## 协议要点

- 服务端 `docs/kimi-openapi.json` / `docs/kimi-asyncapi.json`
- WS 认证在握手 HTTP 头；10s JSON ping 回同 nonce pong；流式为 transcript.ops 帧
- POST prompts 顶层必须带 model；模式字段随 prompts 下发
- 系统注入的 user 消息（<system-reminder> 等）不渲染

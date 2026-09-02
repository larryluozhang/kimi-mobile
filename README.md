# Kimi Mobile

[Kimi Code](https://github.com/MoonshotAI/kimi-code) 本地服务端（`kimi web`）的**非官方**多平台原生客户端：Android / macOS / iOS。

直接消费服务端的 REST + WebSocket API（不内嵌 Web UI），支持远程（如 Tailscale 内网）连接多台主机上的 Kimi Code 服务。

## 功能

- **主机档案**：多台 Kimi Code 服务端切换（地址 + Bearer Token）
- **会话管理**：工作区切换、会话列表（busy 徽标、30s 自刷）
- **流式聊天**：WS `transcript.ops` 实时渲染正文/思考/工具调用流水（🔧 工具名+摘要）
- **排队/执行中/未送达**：busy 时消息排队可见；排队→执行中状态流转；被服务端丢弃的消息标记"未送达"
- **审批与问答**：工具权限审批（批准/拒绝）、AskUserQuestion 问答卡片（单选/其他/跳过）
- **中断**：busy 时一键 `:abort` 中止当前 turn
- **会话模式栏**：计划/Swarm/权限/模型/目标（与官方 Web UI 机制一致，见下）
- Android 另支持语音输入（中文）

## 架构

```
┌─────────────┐   REST (/api/v1/*)    ┌──────────────────┐
│  Android    │ ────────────────────▶ │                  │
│  (Kotlin)   │                       │                  │
├─────────────┤   WS (/api/v1/ws)     │  kimi web        │
│  macOS      │ ────────────────────▶ │  (Kimi Code      │
│  (Compose   │   client_hello →      │   本地服务端)     │
│   Desktop)  │   subscribe_v2 →      │                  │
├─────────────┤   transcript.ops      │  REST + WS +     │
│  iOS        │ ────────────────────▶ │  会话/队列/agent  │
│  (SwiftUI)  │                       │                  │
└─────────────┘                       └──────────────────┘
```

- 三端独立实现、协议共享：`android/`（Kotlin + OkHttp）、`macos/`（Kotlin Compose Desktop，手写 NIO WS 传输层）、`ios/`（SwiftUI + XcodeGen）
- 会话模式（plan/swarm/权限/模型/目标）按官方 Web UI 机制：客户端本地持久化（按 sessionId）+ 每条 `POST /prompts` 顶层随带（服务端 `GET /profile` 不回显，详见协议笔记）

## 构建

| 端 | 要求 | 命令 |
|---|---|---|
| Android | Android SDK 34, JDK 17+ | `cd android && ./gradlew assembleRelease` |
| macOS | JDK 17+（项目内 gradle wrapper） | `cd macos && ./gradlew clean packageDmg` |
| iOS | Xcode 15+, XcodeGen | `cd ios && xcodegen generate && ./redeploy.sh` |

Android 签名见 `android/README.md`；iOS 需在 `project.yml` 填自己的 `DEVELOPMENT_TEAM`。

## 协议文档（本项目的高价值部分）

- [`docs/kimi-openapi.json`](docs/kimi-openapi.json) / [`docs/kimi-asyncapi.json`](docs/kimi-asyncapi.json)：服务端官方 REST/WS 接口文档（`GET /openapi.json`、`GET /asyncapi.json` 导出）
- [`docs/PROTOCOL-NOTES.md`](docs/PROTOCOL-NOTES.md)：**实测协议笔记与踩坑记录**——队列语义、answers schema、transcript.ops 订阅行为、幻影 busy、心跳差异等。这些是官方文档没有、只能靠实测得到的行为细节，对接服务端前必读

## 免责声明

本项目是第三方客户端，与 Moonshot AI 无关联。Kimi Code 服务端为闭源软件，协议行为以实测为准，可能随版本变化（本项目实测基线：0.35.0 ~ 0.37.2）。

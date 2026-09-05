# Kimi Mobile

[Kimi Code](https://github.com/MoonshotAI/kimi-code) 本地服务端（`kimi web`）的非官方多平台原生客户端：Android / macOS / iOS。

- 多主机档案（地址 + Token）、会话/工作区切换、WS 流式聊天、工具流水、审批/问答卡片、中断按钮、排队/执行中/未送达状态、会话模式栏、版本显示

## 仓库结构

- `android/` — Android 客户端（Kotlin）
- `macos/` — Compose Desktop 客户端（Kotlin）
- `ios/` — iOS 客户端（SwiftUI + XcodeGen）
- `docs/` — 服务端接口文档 + 实测协议笔记（`docs/PROTOCOL-NOTES.md` 必看）

构建方式见各子目录 README。协议行为以实测为准（基线：服务端 0.35.0–0.37.2）。

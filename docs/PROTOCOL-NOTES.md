# Kimi Code 服务端协议实测笔记

> 本文档记录开发 Kimi Mobile 三端客户端过程中，对 Kimi Code 本地服务端（`kimi web`，REST + WebSocket）的**实测行为**。官方 OpenAPI/AsyncAPI 文档（见 `kimi-openapi.json` / `kimi-asyncapi.json`）没有的坑都在这里。实测基线：服务端 0.35.0 / 0.37.2。

## 1. 会话列表

`GET /api/v1/sessions`

- `page_size` 上限 **100**，超限返回 40001
- 响应只有 `items` + `has_more`，**没有 total**
- 支持 `busy=true|false` 过滤。**客户端列表查询不要带 `busy=false`**——运行中/卡审批的会话会被滤掉，表现为"会话丢失"（我们踩过）
- `busy` 表示会话是否有活跃 turn；`pending_interaction` 为 `none|approval|question`

## 2. 消息历史

`GET /api/v1/sessions/{id}/messages`

- 返回**最新在前**，客户端需反转为时间正序
- **排队中的 prompt 不进历史**（见下节）
- 系统注入的 user 消息要过滤，否则显示为"用户发的"：以 `<system-reminder>`、`<cron-fire`、`<notification` 开头的文本块（官方 Web UI 同样隐藏）

## 3. 发送与队列（最大的坑）

`POST /api/v1/sessions/{id}/prompts`

- 顶层必须带 `model`；模式字段（`plan_mode/swarm_mode/permission_mode/thinking/goal_*`）随每条 prompt 顶层随带（**只带非默认字段**，与官方 Web UI 的 localStorage 机制一致）
- 响应 `data.status`：
  - `running`：立即执行
  - **`queued`：会话 busy，消息排队**——此时消息**不进 `GET /messages`**，要等轮到它执行才出现
- 队列查询：`GET /api/v1/sessions/{id}/prompts?status=queued` → `data: {active, queued[]}`
  - `active` 是**当前正在执行的 prompt**（0.37.2 起**不在** `queued[]` 里）
  - `queued[]` 每条含 `prompt_id/content[].text/created_at`
- 客户端正确做法：发送后若 `status=queued`，显示"排队中"；以**服务端队列为真相来源**调和本地乐观回显——历史已含→确认，队列有→排队中，active 匹配→执行中，都没有且超 60s→可能被丢弃标"未送达"

### 幻影 busy / 僵尸 active（严重）

实测遇到过多次：会话的 active 槽位被一个**早已完成或异常终止的 prompt** 永久占住（`status: running` 超过 24 小时），此后所有新 prompt 全部排队、永不执行。`GET /sessions/{id}` 此时可能显示 `busy:false`（槽位状态与 busy 标志不同步）。

- **解药**：`POST /api/v1/sessions/{id}:abort`（注意是**冒号后缀**语法，不是 `/abort`）——杀掉当前 active 并自动顶上一条排队 prompt；重复调用可逐条疏通整个积压队列
- 我们已上报上游：[MoonshotAI/kimi-code#3127](https://github.com/MoonshotAI/kimi-code/issues/3127)
- 触发条件疑似与"同一数据目录跑多个服务端进程"或"turn 被异常打断"有关

### 会话动作后缀

`POST /api/v1/sessions/{id}:{action}`，如 `:abort`。`/abort`、`/interrupt` 等路径形式均返回 40001 "unsupported action"。

## 4. 审批（approvals）

- 查询：`GET /api/v1/sessions/{id}/approvals?status=pending` → `items[]`（`approval_id/tool_name/action/tool_input_display.summary/created_at/expires_at`）
- 响应：`POST /api/v1/sessions/{id}/approvals/{approval_id}`，body `{"decision":"approved"|"rejected"|"cancelled"}`
  - `approved`/`rejected`：允许/拒绝该工具调用，agent 继续
  - **`cancelled`：中止整个 turn**
- 服务端无 WS 主动推送审批（或不稳定），客户端用 5s 轮询即可

## 5. 问答（questions）

agent 调用 AskUserQuestion 时 `pending_interaction=question`。

- 查询：`GET /api/v1/sessions/{id}/questions?status=pending` → `items[]`，每项：
  ```json
  {
    "question_id": "question_xxx",
    "questions": [{
      "id": "q_0", "question": "...", "header": "...",
      "options": [{"id": "opt_0_0", "label": "...", "description": "..."}],
      "allow_other": true
    }]
  }
  ```
- 回答：`POST /api/v1/sessions/{sid}/questions/{question_id}`，body 为 record（zod schema 实测）：
  ```json
  {
    "answers": {
      "q_0": {"kind": "single", "option_id": "opt_0_0"},
      "q_1": {"kind": "other", "text": "自由文本"},
      "q_2": {"kind": "skipped"}
    }
  }
  ```
  `kind` 全部取值：`single`（option_id）、`multi`（option_ids）、`other`（text）、`multi_with_other`、`skipped`

## 6. 会话模式 profile（0.35.0 的坑）

- `GET /sessions/{id}/profile` 的 `toWireSession` **硬编码** `agent_config: {model: ""}`——**永远不回显真实模式状态**
- 正确机制（与官方 Web UI 一致）：客户端按 sessionId 本地持久化 + 每条 `POST /prompts` 顶层随带模式字段
- `POST /profile` 的补丁对运行中 agent **真实生效**（`applyAgentConfig`）
- 目标控制：`POST /profile`，body `{"agent_config":{"goal_control":"pause|resume|cancel"}}`；无 goal 时报 40914

## 7. WebSocket

端点 `/api/v1/ws`，握手带 `Authorization: Bearer <token>`。

### 握手与订阅

```
→ {"type":"client_hello","id":"h-x","payload":{"client_id":"..."}}
← {"type":"server_hello","payload":{"protocol_version":2,"heartbeat_ms":10000,...}}
← {"type":"ack","id":"h-x","code":0}
→ {"type":"subscribe_v2","id":"s-x","payload":{"session_id":"...","transcript":{"*":"delta"}}}
← {"type":"transcript.reset", ...}（可能多条，多 agent 各一条）
← {"type":"ack","id":"s-x"}
```

### 心跳

- 服务端每 10s 发 JSON `{"type":"ping","payload":{"nonce":...}}`，客户端须回 `{"type":"pong","payload":{"nonce":...}}`
- **实测 0.34.0 不发心跳**（0.35.0/0.37.2 正常）。客户端不能只靠"N 秒无包即断"判活——我们的修法：空闲 15s 主动发协议级 WS ping（0x9），35s 无帧才重连

### transcript.ops 事件流

`{"type":"transcript.ops","payload":{"agent_id":"main","ops":[...]}}`，ops 类型：

- `frame.upsert`：新内容帧。`frame.kind`：
  - `text`（`role=assistant` 才是正文；user 帧含系统注入要过滤）
  - `thinking`（思考过程）
  - **`tool`（工具调用流水）**：`name/state(running|done)/input/inputText/display`
- `append`：向已有帧追加文本（`target.frameId` + `offset` + `text`）
- `meta.merge`：agent 阶段 `phase.kind`（`running/streaming/ended/interrupted`，`stream=thinking|text`）
- `turn.upsert`：turn 状态（`running/completed/failed/cancelled`）

### 关键坑：迟到订阅者收不到进行中的 turn（0.37.2）

WS 订阅者**只能收到订阅之后新开始的 turn 的 transcript.ops**。打开一个正在运行的会话：无任何流式内容，连 turn.upsert 完成事件也没有。但 `transcript.reset` 的 `payload.snapshot.meta.agent.phase` 含实时阶段（如 `{"kind":"tool_call","turnId":N,...}`）。

客户端对策：
- 进会话时解析 reset 快照的 phase 立即显示"工作中"
- busy 期间**每 15s 轮询 `GET /messages`**（含队列调和），turn 完成后 15s 内可见回复，不依赖收不到的 turn.upsert

### 多实例

同一 `~/.kimi-code` 可跑多个服务端（`~/.kimi-code/server/instances/` 注册），共享磁盘会话历史但**内存队列/agent 状态各自独立**。两个进程跑同一会话会造成分裂脑（队列互相不可见、active 僵尸）。官方未加锁——**务必一个数据目录只跑一个服务端**。

## 8. 其他

- `GET /api/v1/meta`：`server_version`、`capabilities`、`server_id`——排查问题时先确认连的是哪个进程/版本
- `page_size` 上限 100；错误响应 `{"code":40001,"msg":...,"details":[{"path","message"}]}`（zod 详情可直接用于调试）
- token：`~/.kimi-code/server.token`（home 目录共享，可用 `kimi web rotate-token` 轮换）

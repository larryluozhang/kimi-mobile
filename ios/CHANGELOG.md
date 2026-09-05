# 版本记录

## 0.6.2 (2026-09-05)
- 修复 / 命令按首 token 匹配（/fork 带参数不再被当普通消息发送）

## 0.6.1 (2026-09-05)
- 离线语音模型改按需下载（对齐 Android）：模型不再打进 bundle（project.yml 去掉 Resources/models 引用，目录只留 .gitkeep）；SpeechOnnx.modelAvailable 改查运行时目录 Application Support/models/zipformer-bilingual/；设置页新增「离线语音模型」区块：模型状态、可编辑下载地址（UserDefaults voice_model_url，默认 GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip）、「下载离线模型」按钮 + 进度文本（ModelDownloadManager：URLSessionDownloadTask 下载到 tmp → ZIPFoundation 解压到 Application Support，新增 ZIPFoundation SwiftPM 依赖）；语音按钮在仅离线模式且模型未下载时提示「请先到设置页下载离线模型」
- 新增 / 命令支持（发送前拦截，对齐 macOS/Android）：/compact→POST :compact、/archive→:archive（成功返回列表）、/fork→:fork（解析 data.id/session_id 切到新会话）、/abort（/stop）→:abort、/new→返回列表并新建会话（通知 MainView）、/help→命令列表弹窗；其他 / 开头输入当普通 prompt 发送；APIClient 新增通用方法 sessionAction（POST /sessions/{id}:{action}，abortSession 改复用）

## 0.6.0 (2026-09-05)
- 新增自载离线语音识别：sherpa-onnx（SwiftPM 依赖，Apache-2.0）+ streaming-zipformer 中英双语流式模型（int8，约 189MB，放 KimiMobile/Resources/models/zipformer-bilingual/，folder reference 打进 bundle，目录已加入 .gitignore 不入 git）；新增 SpeechOnnx（16kHz 单声道重采样 + 流式喂入 + 端点检测分段，接口对齐 SpeechInput）
- 设置页「偏好」新增语音识别引擎选择（自动优先离线 / 仅离线 / 仅系统，存 UserDefaults voice_engine）；语音按钮 auto 模式下模型存在时优先走离线识别，加载失败自动回退 SFSpeechRecognizer（旧 SpeechInput 代码保留）

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）


## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）
## 0.5.0 (2026-09-02)
- 新增审批 UI：轮询 GET /sessions/{id}/approvals?status=pending（data.items[] 含 approval_id/tool_name/action/tool_input_display.summary），审批卡片显示工具名·动作+摘要，批准/拒绝按钮 POST /sessions/{id}/approvals/{approval_id}（body {"decision":"approved"|"rejected"}）；提交时乐观移除卡片，失败回滚轮询
- 新增问答支持：轮询 GET /sessions/{id}/questions?status=pending（data.items[] 含 question_id、questions[]（每题 id/question/header/options/allow_other））；问答卡片逐题单选（kind=single/option_id），allow_other=true 时提供"其他"文本框（kind=other/text）；提交 POST /sessions/{id}/questions/{question_id}（body {"answers":{"<问题id>":{...}}}），另有"跳过"按钮（全部题目 kind=skipped）
- 新增中断按钮：busy 时输入栏显示红色停止按钮，点击调 POST /sessions/{id}:abort（冒号后缀语法，服务端实测返回 {"aborted":true}），发送按钮保留可用（busy 时仍可排队）
- 审批/问答随历史调和同一周期轮询（busy 15s / 空闲 60s，startBusyPolling 同节奏），onAppear 首次即拉一次；拉取失败不清空已有卡片避免闪动

## 0.4.9 (2026-08-21)
- 设置页「偏好」底部新增版本号显示（"版本 0.4.9 (2)"），取自 Bundle.main 的 CFBundleShortVersionString / CFBundleVersion，随 project.yml 的 MARKETING_VERSION / CURRENT_PROJECT_VERSION 自动生成

## 0.4.8 (2026-08-20)
- 修复"对话顺序错乱"：applyHistory 调和原来把本地回显/排队中/执行中气泡无条件堆在列表末尾，早于最新历史条目时顺序就乱；ChatMessage 已有 createdAt，APIClient.listQueuedPrompts 改返回带 created_at 的 QueuedPrompt 结构（active/queued 均含），调和完成后全列表按 createdAt 升序排列（无时间戳的排最后）
- 修复"空闲会话徽标状态冻结"：busy 轮询原来只在忙时跑；busyPollTask 改为 busy 时 15s 一轮、空闲时 60s 一轮兜底调和（onAppear 启动、视图消失停止、幂等防叠加），转闲不再停轮询
- applyHistory 调和入口新增 [Reconcile] 前缀诊断日志：history 条数、queue/active 摘要、每个本地回显的判定（历史确认/执行中/排队中/未送达/POST 在途）
- 与 macOS 端 0.4.8 同步修复

## 0.4.7 (2026-08-20)
- 修复"turn 进行中才进入会话时无任何流式内容/状态"（v0.37.2 服务端实测：迟到订阅者收不到该 turn 的 transcript.ops）：
  - WSService 处理 transcript.reset 时解析 payload.snapshot.meta.agent.phase 补发 .phase 事件（多 agent 多个 reset 只取 main：agent_id=="main" 或 meta 非空），ChatViewModel 据此立即显示"工作中/正在思考"（phase kind 新增 tool_call 识别）
  - busy 期间每 15s 轮询一次历史（含队列调和），busy 消失时最后刷一次收尾；视图消失/转闲自动停止，幂等防叠加；work_changed 同样可能收不到时由 phase 事件（running/tool_call/streaming 启动、ended/interrupted 停止）兜底触发；loadHistory 的"流式中不覆盖"守卫放宽为"有流式帧才不覆盖"，无帧的迟到订阅者照常调和
- 修复"正在执行的消息显示成排队中"：GET /prompts?status=queued 的 data.active 是当前正在执行的 prompt（不在 queued[] 里），APIClient.listQueuedPrompts 改返回 (active, queued)；applyHistory 调和时与 active 同文本的本地回显标"执行中"（ChatMessage 新增 isExecuting，ChatView 气泡下小字标记），不再标"排队中"/"未送达"；active 无本地回显的（重进会话/重启 app）补渲染"执行中"气泡

## 0.4.6 (2026-08-20)
- 修复"会话列表 busy 徽标陈旧"：列表只在进入/手动刷新时更新，"运行中"转圈会停留旧状态；MainView 改为 .task 循环每 30s 静默刷新会话列表（只更新 sessions，不动 loading/错误条），视图消失时随 .task 取消自动停止
- 排队消息被服务端丢弃的兜底（上游 bug #3127：幻影 busy 下排队 prompt 被静默丢弃，既不在队列也不进历史）：applyHistory 调和时 pendingLocal 条目若既不在历史也不在队列且已超 60s → 标记"未送达（服务端已丢弃）"（ChatMessage 新增 deliveryFailed），不再显示"排队中"；POST 在途（<60s）正常保留
- ChatView 用户气泡新增"未送达（服务端已丢弃）"红色小字警示；服务端队列重建的 queued-N 气泡本就随每次调和重建，队列与历史都没有时自然消失

## 0.4.4 (2026-08-20)
- 修复"重进会话/杀 app 后排队消息消失"：v0.4.3 的 pendingLocal 只在内存，排队消息虽在服务端队列但 UI 不再显示；改为以服务端队列为真相来源 —— 新增 GET /sessions/{id}/prompts?status=queued（APIClient.listQueuedPrompts），拉历史时同步拉队列调和：历史已确认的回显移除、队列里的回显标"排队中"、队列里本地无回显的补渲染为排队气泡（按文本去重）；发送返回 queued 时本地回显即时标"排队中"
- ChatView 用户气泡新增"排队中"标记（气泡下方小字 + 转圈）

## 0.4.3 (2026-08-20)
- 修复"排队消息被抹"：busy 时 POST /prompts 返回 status="queued"，被排队的用户消息在轮到执行前不进 GET /messages 历史；发送后的本地乐观回显进入 pendingLocal 待确认，历史刷新时服务端已出现相同文本的 user 消息才移除，否则保留在列表末尾；queued 状态条提示"排队中…"；发送失败移除回显（APIClient.sendPrompt 返回 status）
- 新增工具流水可见：WS transcript.ops 中 frame.kind=="tool" 的帧在消息流中显示工具活动条目（"🔧 Bash: date"，running 转圈 / done 标 ✓，summary 取 display.summary ?: inputText ?: input 描述，去换行截断 80 字符），turn 结束历史刷新时临时条目自然消失
- 与 Android 0.4.3 同步修复

## 0.4.2 (2026-08-15)
- 修复"会话丢失"：GET /sessions 不再带 busy=false，正在运行/卡审批的会话恢复在列表中显示（列表行内已有 busy 转圈标记）
- 与 Android / macOS 端同步修复

## 0.4 (2026-08-15) — 首个版本
- SwiftUI 原生客户端，功能与 Android 0.4.1 / macOS 0.4 对齐
- Tailscale 门控（healthz 探测 + 引导页 + 自动重试）
- 会话列表 + 工作区切换（默认 mobile 工作区）
- WS 流式聊天（transcript.ops 全协议；ping/pong；指数退避重连）
- 语音输入（SFSpeechRecognizer zh-CN）
- 多主机档案（预置 我的服务器 / 我的 Mac，token 存 Keychain）
- 会话模式栏：计划/Swarm/权限/模型/目标；模式状态按会话本地持久化（UserDefaults）+ prompts 随带模式字段（官方机制）
- 幻影消息过滤（<system-reminder> 等）；200 包错解析并向用户显示

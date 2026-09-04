# 版本记录

## 0.6.0 (2026-09-05)
- 新增自载离线语音识别引擎 sherpa-onnx（1.13.7 本地 AAR，Apache-2.0）：内置中英双语流式模型 zipformer-bilingual-2023-02-20（int8 量化，约 190MB assets，不入 git）；AudioRecord 16kHz 单声道采集 + 流式识别，partial 显示在状态条，endpoint/停止时最终结果追加到输入框；识别中再点一次麦克风结束并冲刷尾段结果
- 设置页新增"语音引擎"选项（auto/onnx/system，默认 auto）：auto 下模型存在时优先离线，模型缺失或初始化失败自动回退系统 SpeechRecognizer（旧路径保留）；onnx 强制离线、system 强制系统
- 录音权限沿用现有 RECORD_AUDIO 申请逻辑

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）


## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）
## 0.5.0 (2026-09-02)
- 新增问答支持（pending_interaction="question" 场景）：服务端发 AskUserQuestion 时，ChatActivity 每 5s 轮询 GET /questions?status=pending（与审批共用 5s 轮询定时器），有 pending 时弹窗：每题渲染 RadioGroup 单选（label — description），allow_other 时追加"其他（自定义回答）"+文本输入；提交 {"kind":"single","option_id":...} / {"kind":"other","text":...}；"跳过"按钮发送 {"kind":"skipped"}；未作答完不关闭弹窗（手动接管提交按钮校验）；多条 pending 逐条处理（同审批）

## 0.4.9 (2026-08-21)
- 设置页底部显示当前版本号（versionName + versionCode，取自 BuildConfig）

## 0.4.8 (2026-08-20)
- 修复对话顺序错乱：loadHistory 调和此前把本地回显/排队中/执行中气泡无条件追加在列表末尾，早于最新历史条目时顺序错乱；改为按 timeMillis 升序排列（历史用 created_at，queued/active 气泡用队列接口的 created_at，本地回显用发送时间，无时间戳的排最后）再 setAll
- Api.listQueuedPrompts 连 created_at 一起解析：新增 QueuedPrompt(text, createdAt)，PromptQueue.queued/active 均改为 QueuedPrompt
- 空闲也兜底调和：历史轮询改为前台常驻（busy 15s / 空闲 60s，onPause 停止，防止叠加）；WS 稳定后空闲会话的徽标状态（排队/执行中/未送达）不再冻结
- 新增调和诊断日志（tag "Reconcile"）：history 条数、queue/active 摘要、每个本地回显的判定（历史确认/服务端队列/执行中/未送达/POST 在途）

## 0.4.7 (2026-08-20)
- 修复"turn 进行中进入会话看不到任何进展"（v0.37.2 服务端：turn 进行中才订阅的 WS 客户端收不到该 turn 的 transcript.ops，含流式内容和 turn.upsert 完成事件）：
  - transcript.reset 分支解析 payload.snapshot.meta.agent.phase 实时阶段并回调 onPhase（多 agent 的多个 reset 里取 main：agent_id=="main" 或带 meta 的），进会话立即显示"工作中/正在思考"（新增 tool_call 阶段展示）
  - busy 期间每 15s 轮询一次 loadHistory（turnActive 时启动，onPause 停止，防止叠加定时器）；轮询中检测 busy 消失（data.active 为空且队列清空）→ 立即统一刷新；onWorkChanged(false)/phase ended 也兜底刷新。进行中的 turn 完成后 15s 内可见回复，不再依赖收不到的 turn.upsert
- 执行中不再叫"排队中"：GET /prompts?status=queued 的 data.active 为当前正在执行的 prompt（v0.37.2，不在 queued[] 里）；与本地回显匹配 → 标"执行中"小字（ChatMsg 新增 active 字段），重进会话无回显时渲染 active-0 服务端气泡；active 中的消息不标"未送达"
- Api.listQueuedPrompts 返回类型改为 PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- 修复会话列表"运行中"徽标陈旧：resumed 期间每 30s 自动 loadAll()（onPause 停止；loadAll 只读，不打断下拉刷新/输入）
- 排队消息被服务端丢弃的兜底（上游 bug #3127：幻影 busy 下排队 prompt 被静默丢弃）：既不在历史也不在服务端队列、且已存在超过 60s 的本地回显 → 标记"未送达（服务端已丢弃）"（红色警示，不再显示"排队中"）；POST 在途（<60s）正常保留
- ChatMsg 新增 undelivered 字段；queued-* 气泡行为不变（每轮按服务端队列重建，队列与历史都没有即自然消失）

## 0.4.4 (2026-08-20)
- 修复“排队消息重进会话/杀掉 app 后消失”：以服务端队列为真相来源，loadHistory 同时拉 GET /prompts?status=queued，队列中的消息渲染为带“排队中”小字标记的用户气泡（与本地回显同文本去重，只显示一份）
- pendingLocal 调和升级：历史已确认 → 移除；服务端队列中存在 → 转由服务端队列气泡渲染；既不在历史也不在队列（POST 在途）→ 保留
- 队列接口拉取失败时降级为旧行为（保留全部未确认回显），不阻塞历史加载
- POST 返回 queued 时本地回显立即打“排队中”标记（MessageAdapter 新增 markQueued；ChatMsg 新增 queued 字段）

## 0.4.3 (2026-08-20)
- 修复“发送的消息消失”：busy 时服务端返回 queued 且消息暂不进历史，loadHistory 的 setAll 会抹掉乐观回显 → 维护 pendingLocal，未确认回显追加在历史末尾，重连/刷新后仍保留；历史中出现相同文本的 user 消息后自动确认移除
- sendPrompt 返回服务端 status：queued 时状态条提示“排队中，等待当前任务完成…”
- 发送失败时撤回乐观回显（MessageAdapter 新增 removeById）
- 新增工具工作流水：解析 WS frame.upsert 的 kind=tool 帧（display.summary ?: inputText ?: input，截断 80 字符），消息列表插入小字灰色临时条目（running 🔧 / done ✓），状态条显示“工作中：工具名”；turn 结束后随 loadHistory 自然清除

## 0.4.2 (2026-08-15)
- 修复“会话丢失”：listSessions 不再带 busy=false，运行中/待审批会话恢复可见，标题旁显示“运行中”标记
- 新增工具审批：进会话后每 5 秒轮询 pending approvals（仅前台时），弹窗显示工具名+摘要，支持批准/拒绝，多条逐条处理
- 默认工作区改为“上次选择 → session_count 最大的工作区 → 第一个”（旧逻辑写死 Linux 路径，Mac/iOS 主机上误落到 Downloads）
- WorkspaceItem 新增 session_count 解析

## 0.4.1 (2026-08-15)
- 模式状态机制对齐官方 web UI：按会话本地持久化（SharedPreferences），发消息时 prompts 顶层随带模式字段（plan_mode/swarm_mode/permission_mode/model）
- 背景：服务端 v0.35.0 GET /profile 不返回真实 agent_config，回显不可信

## 0.4 (2026-08-15)
- 会话模式栏：计划模式/Swarm 开关、权限模式（手动/自动/YOLO）、模型切换、目标模式（设定/暂停/恢复/取消）
- 修复：明文 HTTP 白名单写死 146 地址导致无法添加其它主机 → 全局放行（自用 tailnet 内网）
- 修复：门控页加常驻"服务器设置"入口，防止连不上时被锁死
- 修复：Tailscale 调起（Android 11+ 包可见性 queries 声明）+ 系统 VPN 设置兜底

## 0.3.2 (2026-08-15)
- 修复用户气泡文字横向截断（布局约束）
- 修复最后一条消息被输入框遮挡（RecyclerView padding + 滚动时机）
- 修复历史消息倒序（API 返回最新在前，需反转）

## 0.3 (2026-08-15)
- 过滤系统注入的幻影用户消息（<system-reminder> / <cron-fire 开头的块）

## 0.2 (2026-08-14)
- Material Design 3 美化：新图标（蓝紫渐变+气泡K）、日夜双主题、splash、聊天气泡重设计、长按复制、代码块渲染

## 0.1 (2026-08-14)
- 首版：Tailscale 门控、会话列表+工作区切换、WS 流式聊天（transcript.ops）、语音输入（SpeechRecognizer zh-CN）、多主机档案

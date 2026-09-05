# 版本记录

## 0.6.2 (2026-09-05)
- 修复 / 命令按首 token 匹配（/fork 带参数不再被当普通消息发送）

## 0.6.1 (2026-09-05)
- 输入框支持 / 斜杠命令（发送前拦截，精确匹配忽略大小写；未识别的 / 开头文本仍当普通 prompt 发送）：
  /compact → POST /sessions/{id}:compact 压缩历史（空历史服务端报 40910，以错误气泡反馈）；
  /archive → POST :archive 归档后清 activeSessionId 并刷新侧边栏；
  /fork → POST :fork 成功后切换到返回的新会话；
  /abort（或 /stop）→ POST :abort 中断当前 turn；/new → 同侧边栏「新会话」流程；/help → 弹出命令说明
- Api.kt 新增 compactSession / archiveSession / forkSession（fork 返回新会话 id）

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）


## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）
## 0.5.0 (2026-09-02)
- 审批 UI：轮询 GET /sessions/{id}/approvals?status=pending（5s 一轮），agent 卡审批时显示审批卡片（工具名/动作/摘要 + 批准/拒绝按钮），
  POST /approvals/{approval_id} 提交 decision（approved/rejected）；agent 在 manual 权限下发起需审批工具调用时挂起，本端决策后服务端恢复运行
- 问答 UI：轮询 GET /sessions/{id}/questions?status=pending（5s 一轮），单选 RadioButton 组渲染 questions[]，allow_other 时追加"其他"+文本输入框，
  "跳过"按钮；answers 为 问题id → 答案对象 的映射（kind=single 带 option_id / other 带 text / skipped）；
  POST /questions/{question_id} 提交后 agent 恢复运行，turn 继续
- 中断按钮：busy 时聊天头部显示"停止"按钮，调 POST /sessions/{id}:abort（实测返回 {"aborted":true}）中断当前 turn

## 0.4.9 (2026-08-21)
- 设置窗口底部显示当前版本号：build.gradle.kts 的 version 为唯一来源，
  构建时由 generateVersionProperties 任务写入资源 version.properties，
  运行时 AppVersion 从 classpath 读取（读取失败显示 "unknown"）

## 0.4.8 (2026-08-20)
- 修复对话顺序错乱：回显/排队中/执行中气泡原先无条件追加到列表末尾，现携带时间戳
  （ChatMessage.timeMillis；Api.QueuedPrompt 带 created_at），reconcile 输出按时间排序（旧→新）
- 徽标错乱排查：reconcileHistory 增加 RECONCILE 诊断日志（调和输入 + 每个回显的判定结果）；
  refreshHistory/进会话加载增加跨会话竞态防护（拉取期间切走则丢弃结果）
- 空闲会话也每 60s 兜底调和一次（busy 时仍 15s）——WS 探活修复后不再有重连刷新，
  空闲会话的排队/执行中/未送达状态只能靠周期调和保持新鲜

## 0.4.7 (2026-08-20)
- 修复打开正在运行的会话界面死寂（实测 v0.37.2：turn 进行中才订阅的 WS 收不到该 turn 的
  任何 transcript.ops，连 turn.upsert 完成事件都没有）：
  WsClient 处理 transcript.reset 时解析 payload.snapshot.meta.agent.phase 实时阶段
  （running/streaming/tool_call/ended…；多 agent 多条 reset 只取 main agent——
  agent_id=="main" 或无 agent_id 且 meta 非空），MainScreen 据此立即亮"工作中"状态并落 busy
- busy 期间每 15s 轮询历史（含队列调和）：进行中的 turn 完成时 15s 内即可看到回复，
  不再依赖收不到的 turn.upsert；onWorkChanged(false) 立即做最后一次对齐，
  轮询发现服务端已无 active/queued（busy 消失但事件缺失）时落 busy 并做最后一次对齐；
  轮询为 LaunchedEffect(sessionId)，同一会话不会叠加多个定时器
- 执行中不再叫"排队中"：GET /prompts?status=queued 的 data.active 是当前执行中的 prompt
  （v0.37.2 起不在 queued[] 里），Api.listQueuedPrompts 返回 PromptQueue(queued, active)；
  调和时 active 文本匹配本地回显 → 标"执行中"（样式随"排队中"），active 中的消息绝不标"未送达"；
  无本地回显的 active 条目（其他端提交）重建为"执行中"气泡

## 0.4.6 (2026-08-20)
- 修复侧边栏 busy 徽标陈旧：会话列表只在进会话/turn 结束时刷新，其他会话的"运行中"标记会停留在旧状态；
  MainScreen 增加 30s 定时器周期调用 loadSidebar（historyLoading/加载中途跳过，不打断当前 UI），
  WS onOpen 重连成功时也顺带刷一次侧边栏
- 排队消息被服务端丢弃的兜底（上游 MoonshotAI/kimi-code#3127：幻影 busy 下排队 prompt 被静默丢弃）：
  PendingEcho 记录创建时间戳；调和时回显既不在历史也不在服务端队列且已存在超 60s → 标记"未送达（服务端已丢弃）"
  红色警示，不再显示"排队中"假状态；POST 在途（<60s）仍原样保留，queued 状态以服务端队列为准；
  队列重建气泡（queued-$sessionId-hash）本轮队列与历史都没有时直接消失（真在队列下轮会重建）

## 0.4.5 (2026-08-20)
- 修复 WS 每 ~40s 被看门狗断开重连（累计上万次，每次重连全量拉历史）：
  实测 v0.37.2 服务端在非 loopback（Tailscale IP）连接上不发心跳 ping，
  原 35s 无包即断的被动判活不适用；改为空闲 15s 主动发协议级 ping(0x9) 探活，
  35s 仍无任何帧才判定掉线重连（已用 --wsprobe 探针验证连接可稳定保持）

## 0.4.4 (2026-08-20)
- 修复切换会话排队回显被永久抹掉：pendingEchoes 按 sessionId 隔离，LaunchedEffect 不再无脑 clear
- 以服务端队列为真相来源：新增 GET /sessions/{id}/prompts?status=queued（Api.listQueuedPrompts），
  历史刷新（进会话、turn 结束、WS 重连）时同时拉取队列调和——
  历史已确认的回显移除；队列中的显示为“排队中”气泡（本地回显与队列条目同文本去重只显示一份）；
  既不在历史也不在队列的回显（POST 在途）保留；其他端提交/重启前排队的消息也能重建出“排队中”气泡
- 排队中 user 气泡带“排队中”小标记

## 0.4.3 (2026-08-20)
- 修复排队回显被抹：sendPrompt 返回 status（running/queued）；本地回显登记为 pendingEchoes，
  历史刷新（turn 结束、WS 重连）按文本确认移除，未确认的保留在列表末尾；发送失败移除回显
- queued 时头部状态区提示“排队中…”
- 工具流水可见：WS frame.upsert 中 kind="tool" 的帧渲染为工具活动条目（🔧 名称: 摘要，done 标 ✓），
  摘要取 display.summary ?: inputText ?: input，去换行截断 80 字符

## 0.4.2 (2026-08-15)
- 修复会话丢失：listSessions 不再带 busy=false（运行中/卡审批会话被过滤）
- 会话列表 busy=true 显示“运行中”标记（小圆点 + 文案）

## 0.4 (2026-08-15)
- 会话模式栏：计划/Swarm 开关、权限模式、模型下拉、目标模式（创建/暂停/恢复/取消）
- 模式状态按官方机制实现：按会话本地持久化（config.properties），prompts 顶层随带模式字段
- 服务端发现：GET /profile 不回传真实 agent_config（v0.35.0 硬编码空壳）
- 新增 --e2e-profile 自检入口

## 0.3.1 (2026-08-15)
- 修复历史消息倒序（API 返回最新在前，需反转）
- Enter 发送 / Shift+Enter 换行

## 0.3 (2026-08-15)
- 业务错误（HTTP 200 包 code!=0）显示红色错误气泡
- 幻影消息过滤（<system-reminder> 等系统注入 user 消息）
- WS 帧级日志；--e2e 全链路自检

## 0.2 (2026-08-14)
- 全链路文件日志 ~/.kimi-mobile/app.log
- 门控重试循环 catch Throwable；启动自动选中最近会话建立 WS

## 0.1 (2026-08-14)
- 首版（Compose Multiplatform Desktop）
- 手写 NIO 传输层（MiniHttp/WsClient）绕过本机 OCLP 的 JVM 网络栈缺陷（java.net/OkHttp 连得上读不到数据）
- Tailscale 门控、会话/工作区、WS 流式聊天、多主机档案

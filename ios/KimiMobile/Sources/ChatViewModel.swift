import Foundation
import Combine

/// 聊天页状态机：历史消息 + WS 流式渲染（逻辑与 Android/macOS 版一致）。
@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var statusText: String?
    @Published var busy = false
    @Published var toast: String?
    /// 会话 agent 配置（模式栏回显）；nil = 尚未加载
    @Published var agentConfig: AgentConfig?
    /// 模式栏正在保存（禁用控件防连点）
    @Published var profileSaving = false
    /// 上下文使用量（token）。数据源优先级：① WS transcript.reset 快照 →
    /// ② transcript.ops meta.merge（两路同为 WS 事件，后到的自然覆盖）→
    /// ③ GET /sessions/{id} usage 兜底（实测可能全 0）
    @Published var contextUsed: Int?
    @Published var contextLimit: Int?

    let sessionId: String
    /// 会话标题（/rename 成功后本地刷新，故为 @Published）
    @Published var sessionTitle: String

    private let store: ProfileStore
    private var ws: WSService?
    /// 当前 turn 的 assistant 文本帧（保持插入顺序）
    private var frameOrder: [String] = []
    private var frameTexts: [String: String] = [:]
    private var turnActive = false
    /// 周期历史轮询任务（含队列调和）：busy 时 15s 一轮、空闲时 60s 一轮兜底；
    /// 视图消失时取消，onAppear 重启；幂等防叠加
    private var busyPollTask: Task<Void, Never>?
    /// 待确认的本地乐观回显（queued 的用户消息在轮到执行前不进服务端历史；
    /// 历史刷新时以服务端历史 + GET /prompts?status=queued 队列调和，见 applyHistory）
    private var pendingLocal: [ChatMessage] = []

    /// 待审批项（GET /approvals?status=pending 轮询结果，UI 卡片展示）
    @Published var pendingApprovals: [ApprovalItem] = []
    /// 待答问卷（GET /questions?status=pending 轮询结果，UI 卡片展示）
    @Published var pendingQuestions: [QuestionItem] = []
    /// 正在提交中的审批/回答（禁用按钮防连点）
    @Published var approvalsResponding = false
    @Published var questionsSubmitting = false
    @Published var aborting = false

    // MARK: - / 命令结果（ChatView 配合动作）

    /// /archive 成功：返回会话列表
    @Published var requestExitToList = false
    /// /fork 成功：切到新会话（ChatView navigationDestination 推出）
    @Published var forkTarget: SessionItem?
    /// /help：显示命令列表弹窗
    @Published var showSlashHelp = false
    /// /new：返回列表并新建会话（MainView 监听通知）
    @Published var requestNewSession = false

    /// /help 弹窗内容
    static let slashHelpText = """
    /compact　压缩当前会话上下文
    /archive　归档会话并返回列表
    /fork　分叉当前会话并切换到新会话（同工具栏「分叉」按钮）
    /rename 新名字（或 /title）　修改会话标题
    /abort（或 /stop）　中断当前执行
    /new　新建会话
    /help　显示本列表
    其他 / 开头的输入当作普通消息发送
    """

    /// 上下文用量显示文本，如「上下文 23.5k/1000k (2%)」；数据未就绪时为 nil
    var contextUsageText: String? {
        guard let used = contextUsed, let limit = contextLimit, limit > 0 else { return nil }
        let pct = Int((Double(used) / Double(limit) * 100).rounded())
        return "上下文 \(Self.formatTokens(used))/\(Self.formatTokens(limit)) (\(pct)%)"
    }

    /// token 数缩写：≥1000 用 k（整数不带小数，如 1000k；非整数保留一位，如 23.5k）
    private static func formatTokens(_ n: Int) -> String {
        guard n >= 1000 else { return "\(n)" }
        let k = Double(n) / 1000
        return k.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(k))k" : String(format: "%.1fk", k)
    }

    init(store: ProfileStore, sessionId: String, sessionTitle: String) {
        self.store = store
        self.sessionId = sessionId
        self.sessionTitle = sessionTitle
    }

    // MARK: - 生命周期

    func onAppear() {
        let w = WSService(serverHTTP: store.serverURL, token: store.token, sessionId: sessionId)
        w.onEvent = { [weak self] e in
            Task { @MainActor [weak self] in self?.handle(e) }
        }
        ws = w
        w.start()
        loadHistory()
        loadProfile()
        loadPending() // 首次进会话即拉一次审批/问答，不等待第一轮轮询
        startBusyPolling() // 周期调和：busy 15s / 空闲 60s，空闲会话徽标不冻结
    }

    func onDisappear() {
        busyPollTask?.cancel()
        busyPollTask = nil
        ws?.stop()
        ws = nil
    }

    // MARK: - 历史

    func loadHistory() {
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                // 历史与服务端排队队列一起拉（队列拉取失败不阻塞历史，降级为空）
                async let historyReq = APIClient.getMessages(server: server, token: token, sessionId: sid)
                async let queuedReq = APIClient.listQueuedPrompts(server: server, token: token, sessionId: sid)
                let history = try await historyReq
                let (active, queued) = (try? await queuedReq) ?? (nil, [])
                // 有流式帧在渲染时不覆盖，等 turn 结束后统一刷新；
                // busy 轮询（迟到订阅者收不到 transcript.ops，无流式帧）照常调和
                if turnActive && !frameOrder.isEmpty { return }
                applyHistory(history, active: active, queued: queued)
                // 上下文用量兜底：WS（reset 快照 / meta.merge）是主数据源，
                // 都还没收到时才查 REST usage（实测可能全 0，此时保持不显示）
                if contextUsed == nil || contextLimit == nil { loadContextUsageFallback() }
            } catch let e as APIError {
                handleAPIError(e)
            } catch {
                toast = "加载消息失败：\(error.localizedDescription)"
            }
        }
    }

    /// 上下文用量兜底（GET /sessions/{id} 的 usage.context_tokens/context_limit）；
    /// 全 0 视为无效（保持 nil 不显示）；WS 数据到达后自然以 WS 为准
    private func loadContextUsageFallback() {
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            if let usage = try? await APIClient.getSessionUsage(server: server, token: token, sessionId: sid),
               usage.limit > 0 {
                if contextUsed == nil { contextUsed = usage.used }
                if contextLimit == nil { contextLimit = usage.limit }
            }
        }
    }

    /// 周期轮询历史（含队列调和）：busy 时 15s 一轮（v0.37.2 起 turn 进行中才订阅的 WS
    /// 收不到该 turn 的 transcript.ops，只能靠轮询补齐"执行中"标记与历史）；
    /// 空闲时 60s 一轮兜底——WS 稳定后不再有重连刷新，空闲会话的气泡状态
    /// （排队/执行中/未送达）只能靠周期调和保持新鲜；
    /// 视图消失时取消（onAppear 重启）；幂等（已有任务在跑则不重置计时，防叠加）。
    private func startBusyPolling() {
        guard busyPollTask == nil else { return } // 幂等：phase 事件高频重复，不重置计时
        busyPollTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval: UInt64 = (self?.busy ?? false) ? 15_000_000_000 : 60_000_000_000
                try? await Task.sleep(nanoseconds: interval)
                guard !Task.isCancelled, let self = self else { return }
                self.loadHistory()
                self.loadPending() // 审批/问答随调和周期轮询（busy 15s / 空闲 60s，同历史调和同一节奏）
            }
        }
    }

    /// 用服务端历史 + 服务端排队队列调和列表（服务端队列为排队消息真相来源）：
    /// - pendingLocal 已被历史确认（相同文本的 user 消息）→ 移除；
    /// - 与 data.active（当前正在执行的 prompt，v0.37.2 起不在 queued[] 里）同文本
    ///   → 标记"执行中"（替代"排队中"），不标"未送达"；
    /// - 未确认但在服务端队列里 → 标记"排队中"保留，并从队列清单消费（避免与回显重复渲染）；
    /// - 既不在历史/active/队列 → POST 在途（<60s）原样保留；超过 60s 判定被服务端丢弃
    ///   （上游 bug #3127 幻影 busy 吞排队 prompt）→ 标"未送达"，不再显示"排队中"；
    /// - 队列里本地没有回显的（重进会话/重启 app 后）→ 补渲染为"排队中"用户气泡；
    ///   该 queued-N 气泡每次调和重建，队列与历史都没有时自然消失；
    /// - active 本地没有回显的（重进会话/重启 app 后）→ 补渲染为"执行中"用户气泡（id=active，
    ///   每次调和重建，turn 结束进历史后自然消失）；
    /// - 调和完成后全列表按 createdAt 升序排列（无时间戳的排最后），
    ///   修复回显/排队/执行中气泡无条件堆在列表末尾导致的对话顺序错乱。
    private func applyHistory(_ history: [ChatMessage], active: APIClient.QueuedPrompt?, queued: [APIClient.QueuedPrompt]) {
        // 诊断日志：记录调和输入与每个回显的判定，便于排查徽标/顺序错乱
        print("[Reconcile] history=\(history.count) queued=\(queued.map { String($0.text.prefix(15)) }) "
            + "active=\(active.map { String($0.text.prefix(15)) } ?? "nil") "
            + "echoes=\(pendingLocal.map { String($0.text.prefix(15)) })")
        var remainingHistory = history
        var remainingQueued = queued
        var activeRemaining = active
        var kept: [ChatMessage] = []
        for var pending in pendingLocal {
            if let idx = remainingHistory.firstIndex(where: { $0.role == "user" && $0.text == pending.text }) {
                remainingHistory.remove(at: idx)
                print("[Reconcile] echo「\(pending.text.prefix(15))」→ 历史已确认，移除")
                continue // 已被服务端历史确认
            }
            if let a = activeRemaining, a.text == pending.text {
                // 正在执行：active 优先于 queued/未送达 判定
                activeRemaining = nil
                pending.isExecuting = true
                pending.isQueued = false
                pending.deliveryFailed = false
                print("[Reconcile] echo「\(pending.text.prefix(15))」→ 执行中")
            } else if let q = remainingQueued.firstIndex(where: { $0.text == pending.text }) {
                remainingQueued.remove(at: q)
                pending.isExecuting = false
                pending.isQueued = true
                pending.deliveryFailed = false
                print("[Reconcile] echo「\(pending.text.prefix(15))」→ 排队中")
            } else if let created = pending.createdAt,
                      Date().timeIntervalSince(created) > 60 {
                // 超 60s 仍无着落：服务端已丢弃，标"未送达"（警示），不再假装"排队中"
                pending.isExecuting = false
                pending.isQueued = false
                pending.deliveryFailed = true
                print("[Reconcile] echo「\(pending.text.prefix(15))」→ 未送达（超 60s 无着落）")
            } else {
                print("[Reconcile] echo「\(pending.text.prefix(15))」→ POST 在途，原样保留")
            }
            kept.append(pending)
        }
        pendingLocal = kept
        // 服务端队列里剩余的：跳过历史里已出现的同文本 user 消息（拉取竞态），其余补渲染
        let serverQueued: [ChatMessage] = remainingQueued.enumerated().compactMap { i, q in
            let inHistory = history.contains { $0.role == "user" && $0.text == q.text }
            return inHistory ? nil : ChatMessage(id: "queued-\(i)", role: "user", text: q.text,
                                                 isQueued: true, createdAt: q.createdAt)
        }
        // active 里本地没有回显的（重进会话/重启 app 后）：跳过历史竞态，补渲染"执行中"气泡
        var activeBubble: [ChatMessage] = []
        if let a = activeRemaining,
           !history.contains(where: { $0.role == "user" && $0.text == a.text }) {
            activeBubble = [ChatMessage(id: "active", role: "user", text: a.text,
                                        isExecuting: true, createdAt: a.createdAt)]
        }
        // 按 createdAt 升序（旧→新；无时间戳的排最后），修复回显/排队/执行中气泡
        // 无条件堆在列表末尾导致的对话顺序错乱
        messages = (history + pendingLocal + serverQueued + activeBubble)
            .sorted { ($0.createdAt ?? .distantFuture) < ($1.createdAt ?? .distantFuture) }
    }

    /// 审批/问答轮询（随历史调和同一轮一起拉；拉取失败不清空已有卡片，避免闪动）
    private func loadPending() {
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            async let approvalsReq = APIClient.listPendingApprovals(server: server, token: token, sessionId: sid)
            async let questionsReq = APIClient.listPendingQuestions(server: server, token: token, sessionId: sid)
            if let approvals = try? await approvalsReq {
                pendingApprovals = approvals
            }
            if let questions = try? await questionsReq {
                pendingQuestions = questions
            }
        }
    }

    // MARK: - 审批 / 问答 / 中断

    /// 响应审批（approved / rejected）；提交后乐观移除卡片，等下一轮轮询确认
    func respondApproval(_ item: ApprovalItem, decision: String) {
        guard !approvalsResponding else { return }
        approvalsResponding = true
        pendingApprovals.removeAll { $0.id == item.id }
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                try await APIClient.respondApproval(server: server, token: token, sessionId: sid,
                                                    approvalId: item.id, decision: decision)
            } catch let e as APIError {
                handleAPIError(e)
                loadPending()
            } catch {
                toast = "提交审批失败：\(error.localizedDescription)"
                loadPending()
            }
            approvalsResponding = false
        }
    }

    /// 提交问卷回答。answers 记录：{"<问题id>":{"kind":"single","option_id":"..."} / {"kind":"other","text":"..."}}
    func answerQuestion(_ item: QuestionItem, answers: [String: Any]) {
        guard !questionsSubmitting else { return }
        questionsSubmitting = true
        pendingQuestions.removeAll { $0.id == item.id }
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                try await APIClient.answerQuestion(server: server, token: token, sessionId: sid,
                                                   questionId: item.id, answers: answers)
            } catch let e as APIError {
                handleAPIError(e)
                loadPending()
            } catch {
                toast = "提交回答失败：\(error.localizedDescription)"
                loadPending()
            }
            questionsSubmitting = false
        }
    }

    /// 跳过问卷（全部题目 kind=skipped）
    func skipQuestion(_ item: QuestionItem) {
        var answers: [String: Any] = [:]
        for q in item.questions {
            answers[q.id] = ["kind": "skipped"]
        }
        answerQuestion(item, answers: answers)
    }

    /// 中断当前 turn（POST /sessions/{id}:abort）
    func abort() {
        guard !aborting else { return }
        aborting = true
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                try await APIClient.abortSession(server: server, token: token, sessionId: sid)
                statusText = "已中断"
            } catch let e as APIError {
                handleAPIError(e)
            } catch {
                toast = "中断失败：\(error.localizedDescription)"
            }
            aborting = false
        }
    }

    // MARK: - 会话模式（本地为准 + /profile 补丁）

    /// 模式状态本地持久化为准（服务端 GET /profile 是空壳）；本地无记录才 GET 兜底。
    func loadProfile() {
        if let local = SessionModeStore.load(sessionId: sessionId) {
            agentConfig = local
            return
        }
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                let cfg = try await APIClient.getProfile(server: server, token: token, sessionId: sid)
                agentConfig = cfg
                SessionModeStore.save(sessionId: sid, config: cfg)
            } catch let e as APIError {
                // profile 读失败不阻塞聊天，仅提示
                toast = e.isAuthError ? "Token 无效，请到设置页重新填写" : "读取会话配置失败：\(e.message)"
            } catch {
                toast = "读取会话配置失败：\(error.localizedDescription)"
            }
        }
    }

    /// 修改模式：立即写本地（每条 prompt 会随带，必然生效），
    /// 同时 POST /profile 补丁让运行中的 agent 即时生效；补丁失败仅提示，本地状态不回滚。
    /// goal_control 是控制命令（pause/resume/cancel），只走补丁、不进 prompts 字段。
    func updateProfile(fields: [String: Any], optimistic: ((inout AgentConfig) -> Void)? = nil) {
        guard !profileSaving else { return }
        profileSaving = true
        if let optimistic = optimistic, var cfg = agentConfig {
            optimistic(&cfg)
            agentConfig = cfg
            SessionModeStore.save(sessionId: sessionId, config: cfg)
        }
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                try await APIClient.updateProfile(server: server, token: token, sessionId: sid, fields: fields)
            } catch let e as APIError {
                toast = (e.isAuthError ? "Token 无效，请到设置页重新填写" : e.message)
                        + "（本地模式已保存，将随下一条消息生效）"
            } catch {
                toast = "保存会话配置失败：\(error.localizedDescription)（本地模式已保存，将随下一条消息生效）"
            }
            profileSaving = false
        }
    }

    // MARK: - 发送

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        // / 开头先走命令拦截；未识别的 / 输入当普通 prompt 发送
        if trimmed.hasPrefix("/"), handleSlash(trimmed) { return }
        let local = ChatMessage(id: "local-\(UUID().uuidString)",
                                role: "user", text: trimmed, createdAt: Date())
        messages.append(local)
        pendingLocal.append(local)
        statusText = "正在思考…"
        let server = store.serverURL, token = store.token, sid = sessionId
        // 优先用会话模式里的模型（模式栏可改），否则用全局偏好
        let sessionModel = agentConfig?.model ?? ""
        let model = sessionModel.isEmpty ? store.model : sessionModel
        // 模式字段顶层随带（只带非默认/已设置的），驱动 turn 行为
        let modeFields = agentConfig?.promptFields ?? [:]
        Task {
            do {
                let status = try await APIClient.sendPrompt(server: server, token: token, sessionId: sid,
                                                            text: trimmed, model: model,
                                                            modeFields: modeFields)
                if status == "queued" {
                    statusText = "排队中…"
                    // 本地回显标记"排队中"（列表与 pendingLocal 同步）
                    if let i = messages.firstIndex(where: { $0.id == local.id }) {
                        messages[i].isQueued = true
                    }
                    if let i = pendingLocal.firstIndex(where: { $0.id == local.id }) {
                        pendingLocal[i].isQueued = true
                    }
                }
            } catch let e as APIError {
                removeLocalEcho(local)
                handleAPIError(e)
            } catch {
                removeLocalEcho(local)
                statusText = nil
                toast = "发送失败：\(error.localizedDescription)"
            }
        }
    }

    /// 发送失败：移除本地乐观回显（列表与 pendingLocal 同步清理）
    private func removeLocalEcho(_ local: ChatMessage) {
        pendingLocal.removeAll { $0.id == local.id }
        messages.removeAll { $0.id == local.id }
    }

    // MARK: - / 命令

    /// 拦截 / 开头的命令（对齐 macOS/Android）；返回 true 表示已处理（不再作为 prompt 发送）。
    /// 未识别的 / 输入返回 false，调用方当普通 prompt 发送。
    private func handleSlash(_ text: String) -> Bool {
        let cmd = String(text.split(separator: " ").first ?? "").lowercased()
        switch cmd {
        case "/compact":
            runSessionAction("compact", successToast: "已压缩上下文")
            return true
        case "/archive":
            runSessionAction("archive", successToast: "已归档") { [weak self] _ in
                self?.requestExitToList = true
            }
            return true
        case "/fork":
            fork()
            return true
        case "/rename", "/title":
            // 取首个空格后的全部剩余文本作为新标题；空则提示用法
            let arg = String(text.dropFirst(cmd.count)).trimmingCharacters(in: .whitespaces)
            guard !arg.isEmpty else {
                toast = "用法：/rename 新会话名"
                return true
            }
            renameSession(arg)
            return true
        case "/abort", "/stop":
            abort()
            return true
        case "/new":
            requestNewSession = true
            return true
        case "/help":
            showSlashHelp = true
            return true
        default:
            return false
        }
    }

    /// 分叉当前会话并切换到新会话（/fork 与工具栏「分叉」按钮同路径）
    func fork() {
        runSessionAction("fork") { [weak self] data in
            guard let self = self else { return }
            // 服务端在新会话信息里回 id（兼容 session_id 键）
            let sid = (data["id"] as? String) ?? (data["session_id"] as? String) ?? ""
            guard !sid.isEmpty else {
                self.toast = "已分叉会话，请返回列表查看"
                return
            }
            let title = (data["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "（分支会话）"
            self.forkTarget = SessionItem(id: sid, title: title, updatedAt: "",
                                          busy: false, workspaceId: "")
        }
    }

    /// /rename（或 /title）：改会话标题（POST /sessions/{id}/profile，body 顶层 title）；
    /// 成功刷新导航栏标题并通知会话列表刷新
    private func renameSession(_ newTitle: String) {
        statusText = "正在改名…"
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                try await APIClient.renameSession(server: server, token: token, sessionId: sid, title: newTitle)
                statusText = nil
                sessionTitle = newTitle
                toast = "已改名为「\(newTitle)」"
                NotificationCenter.default.post(name: .kimiSessionRenamed, object: nil)
            } catch let e as APIError {
                statusText = nil
                handleAPIError(e)
            } catch {
                statusText = nil
                toast = "改名失败：\(error.localizedDescription)"
            }
        }
    }

    /// 执行 POST /sessions/{id}:{action}；成功后弹 successToast（可空）并回调 data
    private func runSessionAction(_ action: String, successToast: String? = nil,
                                  onSuccess: (([String: Any]) -> Void)? = nil) {
        statusText = "执行 /\(action)…"
        let server = store.serverURL, token = store.token, sid = sessionId
        Task {
            do {
                let data = try await APIClient.sessionAction(server: server, token: token,
                                                             sessionId: sid, action: action)
                statusText = nil
                if let successToast = successToast { toast = successToast }
                onSuccess?(data)
            } catch let e as APIError {
                statusText = nil
                handleAPIError(e)
            } catch {
                statusText = nil
                toast = "执行 /\(action) 失败：\(error.localizedDescription)"
            }
        }
    }

    // MARK: - WS 事件

    private func handle(_ e: WSEvent) {
        switch e {
        case .open:
            loadHistory()
        case .closed:
            statusText = "连接断开，正在重连…"
        case .authError:
            toast = "Token 无效，请到设置页重新填写"
        case .error(let msg):
            toast = msg
        case .workChanged(let b):
            busy = b
            if b {
                turnActive = true
                if frameOrder.isEmpty { statusText = "工作中…" }
                startBusyPolling()
            } else {
                turnActive = false
                statusText = nil
                // 轮询任务不停：转闲后自动降为 60s 一轮兜底调和，仅视图消失时取消
                loadHistory() // busy 消失最后刷一次（迟到订阅者收不到 turn.upsert，靠这次收尾）
            }
        case .phase(let kind, let stream):
            switch kind {
            case "running", "tool_call":
                statusText = (stream == "thinking") ? "正在思考…" : "工作中…"
                // 迟到订阅者可能只有 reset 快照 phase、收不到 work_changed：同样启动 busy 轮询
                busy = true
                startBusyPolling()
            case "streaming":
                statusText = (stream == "thinking") ? "正在思考…" : nil
                busy = true
                startBusyPolling()
            case "ended", "interrupted":
                statusText = nil
                if busy {
                    // work_changed 可能同样收不到，phase 收尾：转闲（轮询降为 60s）+ 最后刷一次
                    busy = false
                    loadHistory()
                }
            default:
                break
            }
        case .frameUpsert(_, let frameId, let kind, let role, let text):
            switch kind {
            case "text":
                // 只渲染 assistant 正文：user 帧（含系统注入的 <system-reminder>/<cron-fire>）一律不进气泡
                guard role == "assistant" else { return }
                guard frameTexts[frameId] == nil else { return }
                frameOrder.append(frameId)
                frameTexts[frameId] = text
                refreshStreamingBubble()
            case "thinking":
                // thinking 帧只更新状态条，不当正文渲染
                statusText = "正在思考…"
            default:
                break
            }
        case .toolUpsert(let frameId, let name, let state, let summary):
            // 工具流水条目（"🔧 Bash: date"），done 标 ✓；turn 结束历史刷新时随列表替换消失
            let id = "tool-\(frameId)"
            let display = summary.isEmpty ? name : "\(name): \(summary)"
            if let idx = messages.firstIndex(where: { $0.id == id }) {
                messages[idx].text = display
                messages[idx].toolDone = (state == "done")
            } else {
                messages.append(ChatMessage(id: id, role: "tool", text: display,
                                            createdAt: Date(),
                                            toolName: name, toolDone: state == "done"))
            }
        case .frameAppend(let frameId, _, let text):
            guard frameTexts[frameId] != nil else { return }
            frameTexts[frameId]! += text
            refreshStreamingBubble()
        case .turnState(let state, let error):
            switch state {
            case "running":
                turnActive = true
                frameOrder.removeAll()
                frameTexts.removeAll()
            case "completed":
                turnActive = false
                frameOrder.removeAll()
                frameTexts.removeAll()
                statusText = nil
                loadHistory()
            case "failed", "cancelled":
                turnActive = false
                frameOrder.removeAll()
                frameTexts.removeAll()
                statusText = nil
                let msg = (error?.isEmpty ?? true) ? "本轮对话失败（\(state)）" : "出错了：\(error!)"
                messages.append(ChatMessage(id: "err-\(Date().timeIntervalSince1970)",
                                            role: "assistant", text: msg, isError: true,
                                            createdAt: Date()))
            default:
                break
            }
        case .transcriptReset:
            frameOrder.removeAll()
            frameTexts.removeAll()
        case .contextUsage(let used, let limit):
            // 缺失字段保留旧值（merge 增量可能只带其中一个）
            if let used = used { contextUsed = used }
            if let limit = limit { contextLimit = limit }
        }
    }

    private func refreshStreamingBubble() {
        let full = frameOrder.compactMap { frameTexts[$0] }.joined()
        if let idx = messages.firstIndex(where: { $0.id == "stream" }) {
            messages[idx].text = full
        } else {
            messages.append(ChatMessage(id: "stream", role: "assistant", text: full,
                                        isStreaming: true, createdAt: Date()))
        }
    }

    private func handleAPIError(_ e: APIError) {
        if e.isAuthError {
            toast = "Token 无效，请到设置页重新填写"
        } else {
            toast = "请求失败：\(e.message)"
        }
    }
}

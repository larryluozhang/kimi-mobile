import SwiftUI

/// 聊天页：消息气泡 + WS 流式渲染 + 语音输入。
struct ChatView: View {
    @StateObject private var vm: ChatViewModel
    @StateObject private var speech = SpeechInput()

    @State private var input = ""
    @State private var showSpeechDeniedAlert = false
    @FocusState private var inputFocused: Bool

    private let store: ProfileStore
    private let voiceEnabled: Bool

    init(store: ProfileStore, sessionId: String, sessionTitle: String) {
        self.store = store
        self.voiceEnabled = store.voiceEnabled
        _vm = StateObject(wrappedValue: ChatViewModel(store: store,
                                                      sessionId: sessionId,
                                                      sessionTitle: sessionTitle))
    }

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            ModeBarView(vm: vm)
            Divider()
            // 待审批 / 待答问卷卡片（轮询 /approvals 与 /questions?status=pending，服务端无 pending 时整块不渲染）
            if !vm.pendingApprovals.isEmpty {
                approvalsBar
                Divider()
            }
            if !vm.pendingQuestions.isEmpty {
                questionsBar
                Divider()
            }
            messageList
            Divider()
            inputBar
        }
        .navigationTitle(vm.sessionTitle)
        .navigationBarTitleDisplayMode(.inline)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { vm.onAppear() }
        .onDisappear { vm.onDisappear() }
        .alert("提示", isPresented: Binding(
            get: { vm.toast != nil },
            set: { if !$0 { vm.toast = nil } }
        )) {
            Button("好", role: .cancel) { vm.toast = nil }
        } message: {
            Text(vm.toast ?? "")
        }
        .alert("需要麦克风和语音识别权限", isPresented: $showSpeechDeniedAlert) {
            Button("去设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("请在系统设置中允许 Kimi Mobile 使用麦克风和语音识别。")
        }
        .onChange(of: speech.partialText) { text in
            guard speech.isRecording || !text.isEmpty else { return }
            input = text
        }
    }

    // MARK: - 状态条

    @ViewBuilder
    private var statusBar: some View {
        if let status = vm.statusText {
            HStack(spacing: 8) {
                ProgressView().scaleEffect(0.7)
                Text(status).font(.footnote)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
            .foregroundColor(Theme.statusText)
            .background(Theme.statusBackground)
        }
    }

    // MARK: - 审批卡片

    @ViewBuilder
    private var approvalsBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(vm.pendingApprovals) { item in
                ApprovalCard(item: item, submitting: vm.approvalsResponding,
                           onApprove: { vm.respondApproval(item, decision: "approved") },
                           onReject: { vm.respondApproval(item, decision: "rejected") })
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }

    // MARK: - 问答卡片

    @ViewBuilder
    private var questionsBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(vm.pendingQuestions) { item in
                QuestionCard(item: item, submitting: vm.questionsSubmitting,
                            onSubmit: { answers in vm.answerQuestion(item, answers: answers) },
                            onSkip: { vm.skipQuestion(item) })
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }

    // MARK: - 消息列表

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(vm.messages) { msg in
                        MessageBubble(message: msg)
                            .id(msg.id)
                    }
                }
                .padding()
            }
            .onChange(of: vm.messages.count) { _ in scrollToBottom(proxy) }
            .onChange(of: vm.messages.last?.text) { _ in scrollToBottom(proxy) }
            .onTapGesture { inputFocused = false }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let last = vm.messages.last else { return }
        withAnimation(.easeOut(duration: 0.15)) {
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }

    // MARK: - 输入栏

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            if voiceEnabled {
                Button(action: toggleVoice) {
                    Image(systemName: speech.isRecording ? "mic.fill" : "mic")
                        .font(.title3)
                        .foregroundColor(speech.isRecording ? .white : Theme.primary)
                        .padding(8)
                        .background(speech.isRecording ? Theme.error : Color.clear)
                        .clipShape(Circle())
                }
            }

            TextField(vm.agentConfig?.planMode == true ? "计划模式：先讨论方案…" : "输入消息…",
                      text: $input, axis: .vertical)
                .focused($inputFocused)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.assistantBubble)
                .cornerRadius(18)

            if vm.busy {
                // 停止：busy 时显示，调 POST /sessions/{id}:abort（冒号后缀语法，服务端实测返回 {"aborted":true}）
                Button(action: vm.abort) {
                    if vm.aborting {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Image(systemName: "stop.circle.fill")
                            .font(.title2)
                            .foregroundColor(Theme.error)
                    }
                }
                .disabled(vm.aborting)
            }

            Button(action: sendCurrent) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.userBubbleGradient)
            }
            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.bar)
    }

    // MARK: - 动作

    private func sendCurrent() {
        let text = input
        input = ""
        if speech.isRecording { speech.stop() }
        vm.send(text)
    }

    private func toggleVoice() {
        if speech.isRecording {
            speech.stop()
            return
        }
        Task {
            let ok = await speech.requestPermissions()
            if ok {
                speech.start()
            } else {
                showSpeechDeniedAlert = true
            }
        }
    }
}

// MARK: - 气泡

struct MessageBubble: View {
    let message: ChatMessage

    private var isUser: Bool { message.role == "user" }
    private var isTool: Bool { message.role == "tool" }

    var body: some View {
        if isTool {
            // 工具活动条目：左对齐小字灰条，running 转圈 / done 标 ✓
            HStack(spacing: 6) {
                if message.toolDone {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                } else {
                    ProgressView().scaleEffect(0.6)
                }
                Text("🔧 \(message.text)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
                Spacer(minLength: 32)
            }
            .padding(.leading, 8)
        } else {
            HStack(alignment: .top) {
                if isUser { Spacer(minLength: 48) }
                bubble
                if !isUser { Spacer(minLength: 32) }
            }
        }
    }

    private var bubble: some View {
        Group {
            if isUser {
                VStack(alignment: .trailing, spacing: 4) {
                    Text(message.text)
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(Theme.userBubbleGradient)
                        .cornerRadius(16)
                    if message.deliveryFailed {
                        // 服务端已丢弃（上游 #3127 兜底）：红色警示，不再显示"排队中"
                        Text("未送达（服务端已丢弃）")
                            .font(.caption2)
                            .foregroundColor(Theme.error)
                    } else if message.isExecuting {
                        // 与服务端 data.active 匹配：当前正在执行，不是排队
                        HStack(spacing: 4) {
                            ProgressView().scaleEffect(0.6)
                            Text("执行中")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    } else if message.isQueued {
                        HStack(spacing: 4) {
                            ProgressView().scaleEffect(0.6)
                            Text("排队中")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } else if message.isError {
                Text(message.text)
                    .foregroundColor(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Theme.error)
                    .cornerRadius(16)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    MarkdownContent(text: message.text)
                    if message.isStreaming {
                        HStack(spacing: 4) {
                            ProgressView().scaleEffect(0.6)
                            Text("输出中…")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Theme.assistantBubble)
                .cornerRadius(16)
                .shadow(color: .black.opacity(0.05), radius: 2, y: 1)
            }
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = message.text
            } label: {
                Label("复制", systemImage: "doc.on.doc")
            }
        }
    }
}

// MARK: - 审批卡片

/// 审批卡片：tool_name · action + summary，批准/拒绝（POST /sessions/{id}/approvals/{approval_id}）
struct ApprovalCard: View {
    let item: ApprovalItem
    let submitting: Bool
    let onApprove: () -> Void
    let onReject: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "hand.raised.fill")
                    .foregroundColor(.orange)
                Text("需要审批：\(item.toolName)" + (item.action.isEmpty ? "" : " · \(item.action)"))
                    .font(.subheadline).bold()
            }
            if !item.summary.isEmpty {
                Text(item.summary)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(3)
            }
            HStack(spacing: 10) {
                Button("批准") { onApprove() }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                Button("拒绝") { onReject() }
                    .buttonStyle(.bordered)
                    .tint(.red)
                Spacer()
            }
            .disabled(submitting)
        }
        .padding(10)
        .background(Color.orange.opacity(0.08))
        .cornerRadius(12)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.orange.opacity(0.4), lineWidth: 1))
    }
}

// MARK: - 问答卡片

/// 问答卡片：逐题单选（kind=single）；allow_other 时提供"其他"文本框（kind=other）。
/// 提交 body：{"answers":{"<问题id>":{"kind":"single","option_id":"..."} / {"kind":"other","text":"..."}}
struct QuestionCard: View {
    let item: QuestionItem
    let submitting: Bool
    let onSubmit: ([String: Any]) -> Void
    let onSkip: () -> Void

    /// 问题 id -> 已选选项 id
    @State private var selected: [String: String] = [:]
    /// 问题 id -> "其他"文本
    @State private var otherText: [String: String] = [:]

    /// 全部题目已答（选了选项或填了其他文本）才可提交
    private var allAnswered: Bool {
        item.questions.allSatisfy { q in
            selected[q.id] != nil
                || !(otherText[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(item.questions) { q in
                VStack(alignment: .leading, spacing: 6) {
                    if !q.header.isEmpty {
                        Text(q.header)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Text(q.question)
                        .font(.subheadline).bold()
                    ForEach(q.options) { opt in
                        Button {
                            if selected[q.id] == opt.id {
                                selected[q.id] = nil
                            } else {
                                selected[q.id] = opt.id
                                otherText[q.id] = "" // 选了选项就清空其他文本
                            }
                        } label: {
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: selected[q.id] == opt.id
                                      ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(Theme.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(opt.label).font(.subheadline)
                                    if !opt.description.isEmpty {
                                        Text(opt.description)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.primary)
                    }
                    if q.allowOther {
                        TextField("其他…（填写后以文本回答）", text: Binding(
                            get: { otherText[q.id] ?? "" },
                            set: {
                                otherText[q.id] = $0
                                if !$0.isEmpty { selected[q.id] = nil } // 填了其他文本就清选项
                            }))
                        .textFieldStyle(.roundedBorder)
                        .font(.subheadline)
                    }
                }
            }
            HStack(spacing: 10) {
                Button("提交") { submit() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!allAnswered || submitting)
                Button("跳过") { onSkip() }
                    .buttonStyle(.bordered)
                    .disabled(submitting)
                Spacer()
            }
        }
        .padding(10)
        .background(Theme.primary.opacity(0.06))
        .cornerRadius(12)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.primary.opacity(0.3), lineWidth: 1))
    }

    private func submit() {
        var answers: [String: Any] = [:]
        for q in item.questions {
            let other = (otherText[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !other.isEmpty {
                answers[q.id] = ["kind": "other", "text": other]
            } else if let optId = selected[q.id] {
                answers[q.id] = ["kind": "single", "option_id": optId]
            }
        }
        onSubmit(answers)
    }
}

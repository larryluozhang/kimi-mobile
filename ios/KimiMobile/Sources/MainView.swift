import SwiftUI

extension Notification.Name {
    /// 会话内输入 /new：ChatView dismiss 回列表，MainView 监听此通知新建会话
    static let kimiNewSessionRequest = Notification.Name("kimiNewSessionRequest")
    /// 会话内 /rename 成功：MainView 监听此通知静默刷新会话列表标题
    static let kimiSessionRenamed = Notification.Name("kimiSessionRenamed")
}

/// 主界面：工作区切换 + 会话列表 + 新建会话。
struct MainView: View {
    @EnvironmentObject private var store: ProfileStore

    @State private var workspaces: [WorkspaceItem] = []
    @State private var sessions: [SessionItem] = []
    @State private var selectedWorkspace: WorkspaceItem?
    @State private var loading = false
    @State private var errorMessage: String?
    @State private var creating = false

    private var filteredSessions: [SessionItem] {
        guard let w = selectedWorkspace else { return sessions }
        return sessions.filter { $0.workspaceId == w.id || $0.workspaceId.isEmpty }
    }

    var body: some View {
        NavigationStack {
            List {
                if let err = errorMessage {
                    Section {
                        Text(err)
                            .foregroundColor(Theme.error)
                            .font(.callout)
                    }
                }
                Section {
                    ForEach(filteredSessions) { s in
                        NavigationLink {
                            ChatView(store: store, sessionId: s.id, sessionTitle: s.title)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(s.title)
                                        .lineLimit(1)
                                    if let d = APIClient.parseISO(s.updatedAt) {
                                        Text(d, style: .relative)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }
                                Spacer()
                                if s.busy {
                                    ProgressView()
                                        .scaleEffect(0.8)
                                }
                            }
                        }
                    }
                } header: {
                    if selectedWorkspace != nil {
                        Text("共 \(filteredSessions.count) 个会话")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .refreshable { await loadAll() }
            .navigationTitle("Kimi Mobile")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    workspaceMenu
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 16) {
                        Button(action: createSession) {
                            if creating {
                                ProgressView()
                            } else {
                                Image(systemName: "square.and.pencil")
                            }
                        }
                        .disabled(creating || selectedWorkspace == nil)
                        NavigationLink {
                            SettingsView()
                        } label: {
                            Image(systemName: "gearshape")
                        }
                    }
                }
            }
            .overlay {
                if loading && sessions.isEmpty {
                    ProgressView("加载中…")
                } else if !loading && filteredSessions.isEmpty && errorMessage == nil {
                    VStack(spacing: 12) {
                        Image(systemName: "bubble.left.and.bubble.right")
                            .font(.system(size: 44))
                            .foregroundColor(.secondary)
                        Text("暂无会话")
                            .font(.headline)
                        Text("点右上角 ✎ 新建会话")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .task {
            await loadAll()
            // busy 徽标仅靠进入/手动刷新会变陈旧：每 30s 静默刷新会话列表，
            // 视图消失时 .task 自动取消，循环随之停止
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard !Task.isCancelled else { break }
                await refreshSessions()
            }
        }
        // 会话内输入 /new：ChatView 发通知并 dismiss 回列表，这里新建会话
        .onReceive(NotificationCenter.default.publisher(for: .kimiNewSessionRequest)) { _ in
            createSession()
        }
        // 会话内 /rename 成功：静默刷新列表标题
        .onReceive(NotificationCenter.default.publisher(for: .kimiSessionRenamed)) { _ in
            Task { await refreshSessions() }
        }
    }

    // MARK: - 工作区切换

    private var workspaceMenu: some View {
        Menu {
            ForEach(workspaces) { w in
                Button {
                    selectWorkspace(w, persist: true)
                } label: {
                    if w.id == selectedWorkspace?.id {
                        Label(w.name, systemImage: "checkmark")
                    } else {
                        Text(w.name)
                    }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "folder")
                Text(selectedWorkspace?.name ?? "工作区")
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.caption2)
            }
            .font(.subheadline)
        }
    }

    // MARK: - 数据加载

    private func loadAll() async {
        loading = true
        errorMessage = nil
        do {
            async let ws = APIClient.listWorkspaces(server: store.serverURL, token: store.token)
            async let ss = APIClient.listSessions(server: store.serverURL, token: store.token)
            let (w, s) = try await (ws, ss)
            workspaces = w
            sessions = s
            pickDefaultWorkspace()
        } catch let e as APIError {
            errorMessage = e.isAuthError
                ? "Token 无效或已过期，请到设置页重新填写"
                : e.message
        } catch {
            errorMessage = "加载失败：\(error.localizedDescription)"
        }
        loading = false
    }

    /// 定时静默刷新会话列表（只更新 sessions，不动 loading/错误条，避免闪烁）
    private func refreshSessions() async {
        if let s = try? await APIClient.listSessions(server: store.serverURL, token: store.token) {
            sessions = s
        }
    }

    /// 默认选中上次用的工作区；否则选中 mobile 工作区（/tmp/kimi-workspace）；再否则第一个
    private func pickDefaultWorkspace() {
        if let lastId = store.lastWorkspaceId,
           let w = workspaces.first(where: { $0.id == lastId }) {
            selectedWorkspace = w
            return
        }
        if let w = workspaces.first(where: { $0.root == Constants.defaultWorkspaceRoot }) {
            selectedWorkspace = w
            return
        }
        selectedWorkspace = workspaces.first
    }

    private func selectWorkspace(_ w: WorkspaceItem, persist: Bool) {
        selectedWorkspace = w
        if persist { store.lastWorkspaceId = w.id }
    }

    private func createSession() {
        guard let w = selectedWorkspace else { return }
        creating = true
        errorMessage = nil
        Task {
            do {
                let s = try await APIClient.createSession(server: store.serverURL,
                                                          token: store.token,
                                                          workspace: w)
                sessions.insert(s, at: 0)
            } catch let e as APIError {
                errorMessage = e.message
            } catch {
                errorMessage = "新建会话失败：\(error.localizedDescription)"
            }
            creating = false
        }
    }
}

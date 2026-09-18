import SwiftUI

/// 设置页：主机档案增删改 + 单选切换；token 存 Keychain。
struct SettingsView: View {
    @EnvironmentObject private var store: ProfileStore
    /// 首次使用引导（Gate 探测通过但没有 token 时弹出）
    var firstRunHint: Bool = false

    @State private var editing: HostProfile?
    @State private var showEditor = false
    /// 导入服务器弹窗 + 导入成功后的确认提示
    @State private var showImporter = false
    @State private var importNotice = ""
    @State private var showImportNotice = false
    /// 服务端模型列表（GET /api/v1/models）；拉取失败/为空保持空数组，模型行回退手动输入
    @State private var serverModels: [ModelItem] = []

    /// 手动检查更新：进行中 / 有更新时弹提醒 / 其余结果（已是最新或失败）走 alert
    @State private var updateChecking = false
    @State private var updateFound: AppUpdateChecker.Info?
    @State private var updateNotice = ""
    @State private var showUpdateNotice = false

    /// 当前版本号，如 "0.4.9 (2)"；读不到时回退占位。
    static let appVersion: String = {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
        return "\(v) (\(b))"
    }()

    var body: some View {
        Form {
            if firstRunHint {
                Section {
                    Text("首次使用请先在下方选中一台主机并填写 API Token。")
                        .font(.callout)
                        .foregroundColor(.secondary)
                }
            }

            Section {
                ForEach(store.profiles) { p in
                    HStack {
                        Button {
                            store.setActive(p)
                        } label: {
                            HStack {
                                Image(systemName: p.id == store.activeProfile?.id
                                      ? "largecircle.fill.circle" : "circle")
                                    .foregroundColor(Theme.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.name)
                                        .foregroundColor(.primary)
                                    Text(p.url)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                    if store.token(for: p).isEmpty {
                                        Text("未填写 Token")
                                            .font(.caption2)
                                            .foregroundColor(Theme.error)
                                    }
                                }
                            }
                        }
                        Spacer()
                        Button {
                            editing = p
                            showEditor = true
                        } label: {
                            Image(systemName: "pencil.circle")
                                .foregroundColor(Theme.primary)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .onDelete { idx in
                    idx.forEach { store.delete(store.profiles[$0]) }
                }

                Button {
                    editing = nil
                    showEditor = true
                } label: {
                    Label("添加主机", systemImage: "plus")
                }
                Button {
                    showImporter = true
                } label: {
                    Label("导入服务器", systemImage: "square.and.arrow.down")
                }
            } header: {
                Text("主机档案")
            } footer: {
                Text("点圆圈切换当前主机；Token 只保存在本机 Keychain。")
            }

            Section {
                Toggle("语音输入", isOn: $store.voiceEnabled)
                HStack {
                    Text("模型")
                    Spacer()
                    if serverModels.isEmpty {
                        // 模型列表拉取失败/为空（或主机未连通）：回退手动输入
                        TextField(Constants.defaultModel, text: $store.model)
                            .multilineTextAlignment(.trailing)
                            .foregroundColor(.secondary)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    } else {
                        // 服务端动态列表（GET /api/v1/models）；当前值不在列表时补进去
                        Picker("模型", selection: $store.model) {
                            ForEach(settingsModelOptions, id: \.self) { m in
                                Text(m.components(separatedBy: "/").last ?? m).tag(m)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(.secondary)
                    }
                }
            } header: {
                Text("偏好")
            } footer: {
                HStack {
                    Text("版本 \(Self.appVersion)")
                    Spacer()
                    if updateChecking {
                        ProgressView()
                    } else {
                        Button("检查更新", action: checkUpdate)
                    }
                }
            }
        }
        .navigationTitle("设置")
        .sheet(isPresented: $showEditor) {
            ProfileEditorSheet(profile: editing)
                .environmentObject(store)
        }
        .sheet(isPresented: $showImporter) {
            ImportServerSheet { notice in
                importNotice = notice
                showImportNotice = true
            }
            .environmentObject(store)
        }
        .alert("导入服务器", isPresented: $showImportNotice) {
            Button("好") {}
        } message: {
            Text(importNotice)
        }
        .sheet(item: $updateFound) { info in
            UpdateReminderSheet(info: info)
        }
        .alert("检查更新", isPresented: $showUpdateNotice) {
            Button("知道了") {}
        } message: {
            Text(updateNotice)
        }
        .onAppear {
            loadServerModels()
        }
        // 切换/增删主机档案后按新的当前主机重拉模型列表
        .onChange(of: store.revision) { _, _ in loadServerModels() }
    }

    /// 全局模型选项（服务端动态列表）；当前值不在列表时补进去，避免 Picker 选中态落空
    private var settingsModelOptions: [String] {
        var ids = serverModels.map(\.id)
        if !store.model.isEmpty && !ids.contains(store.model) {
            ids.append(store.model)
        }
        return ids
    }

    /// 手动检查更新：有更新弹提醒弹窗；已是最新/失败走 alert
    private func checkUpdate() {
        updateChecking = true
        Task {
            let result = await AppUpdateChecker.checkLatest()
            updateChecking = false
            switch result {
            case .success(let info):
                if info.updateAvailable {
                    updateFound = info
                } else {
                    updateNotice = "已是最新版本"
                    showUpdateNotice = true
                }
            case .failure(let error):
                updateNotice = "检查更新失败：\(error.localizedDescription)"
                showUpdateNotice = true
            }
        }
    }

    /// 按当前主机档案拉模型列表；无 token/拉取失败/为空时保持空数组（UI 回退手动输入）
    private func loadServerModels() {
        let server = store.serverURL, token = store.token
        guard !server.isEmpty, !token.isEmpty else {
            serverModels = []
            return
        }
        Task {
            if let models = try? await APIClient.listModels(server: server, token: token),
               !models.isEmpty {
                serverModels = models
            } else {
                serverModels = []
            }
        }
    }
}

/// 档案编辑弹窗：新增或编辑（名称 / URL / Token）
struct ProfileEditorSheet: View {
    @EnvironmentObject private var store: ProfileStore
    @Environment(\.dismiss) private var dismiss

    let profile: HostProfile?

    @State private var name = ""
    @State private var url = ""
    @State private var token = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("主机") {
                    TextField("名称（如：我的服务器）", text: $name)
                    TextField("http://100.x.x.x:58627", text: $url)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                Section("API Token") {
                    SecureField("Bearer Token", text: $token)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                if let error = error {
                    Section {
                        Text(error).foregroundColor(Theme.error)
                    }
                }
            }
            .navigationTitle(profile == nil ? "添加主机" : "编辑主机")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save)
                }
            }
            .onAppear {
                if let p = profile {
                    name = p.name
                    url = p.url
                    token = store.token(for: p)
                }
            }
        }
    }

    private func save() {
        let n = name.trimmingCharacters(in: .whitespaces)
        var u = url.trimmingCharacters(in: .whitespaces)
        while u.hasSuffix("/") { u.removeLast() }
        if n.isEmpty { error = "请填写名称"; return }
        guard !u.isEmpty, URL(string: u) != nil,
              u.hasPrefix("http://") || u.hasPrefix("https://") else {
            error = "请填写合法的地址（http:// 或 https:// 开头）"
            return
        }
        let p = HostProfile(id: profile?.id ?? UUID().uuidString, name: n, url: u)
        store.upsert(p, token: token.trimmingCharacters(in: .whitespacesAndNewlines))
        dismiss()
    }
}

/// 导入服务器弹窗：粘贴 kimi-mobile://connect 深链或 JSON 卡片，一键建档并设为当前主机
struct ImportServerSheet: View {
    @EnvironmentObject private var store: ProfileStore
    @Environment(\.dismiss) private var dismiss

    /// onOpenURL 打开时预填的内容
    var prefill: String = ""
    /// 导入成功后回调（用于外层弹确认提示）
    var onDone: ((String) -> Void)? = nil

    @State private var text = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                TextEditor(text: $text)
                    .font(.system(.body, design: .monospaced))
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .frame(minHeight: 140)
                    .padding(4)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.3))
                    )
                    .overlay(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("粘贴 kimi-mobile://connect 链接或 JSON 卡片")
                                .font(.callout)
                                .foregroundColor(.secondary)
                                .padding(.top, 12)
                                .padding(.leading, 9)
                                .allowsHitTesting(false)
                        }
                    }
                if let error = error {
                    Text(error)
                        .font(.callout)
                        .foregroundColor(Theme.error)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("导入服务器")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("导入", action: importNow)
                }
            }
            .onAppear {
                if text.isEmpty { text = prefill }
            }
        }
    }

    private func importNow() {
        switch ServerImporter.parse(text) {
        case .failure(let e):
            error = e.errorDescription
        case .success(let payload):
            let p = store.importServer(name: payload.name, url: payload.url, token: payload.token)
            dismiss()
            onDone?("已导入「\(p.name)」并设为当前主机")
        }
    }
}

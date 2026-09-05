import SwiftUI

/// 设置页：主机档案增删改 + 单选切换；token 存 Keychain。
struct SettingsView: View {
    @EnvironmentObject private var store: ProfileStore
    /// 首次使用引导（Gate 探测通过但没有 token 时弹出）
    var firstRunHint: Bool = false

    @State private var editing: HostProfile?
    @State private var showEditor = false
    /// 模型下载地址（UserDefaults voice_model_url）
    @State private var modelURL = ModelDownloadManager.modelURL
    @State private var modelInstalled = SpeechOnnx.modelAvailable
    @State private var downloadError: String?
    @StateObject private var downloader = ModelDownloadManager()

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
            } header: {
                Text("主机档案")
            } footer: {
                Text("点圆圈切换当前主机；Token 只保存在本机 Keychain。")
            }

            Section {
                Toggle("语音输入", isOn: $store.voiceEnabled)
                Picker("语音识别引擎", selection: $store.voiceEngine) {
                    Text("自动（优先离线）").tag("auto")
                    Text("离线模型").tag("onnx")
                    Text("系统识别").tag("system")
                }
                HStack {
                    Text("模型")
                    Spacer()
                    TextField(Constants.defaultModel, text: $store.model)
                        .multilineTextAlignment(.trailing)
                        .foregroundColor(.secondary)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
            } header: {
                Text("偏好")
            } footer: {
                Text("版本 \(Self.appVersion)")
            }

            Section {
                HStack {
                    Text("模型状态")
                    Spacer()
                    Text(modelInstalled ? "已安装" : "未下载")
                        .foregroundColor(modelInstalled ? .green : .secondary)
                }
                TextField("模型下载地址", text: $modelURL)
                    .font(.caption)
                    .keyboardType(.URL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .onChange(of: modelURL) { ModelDownloadManager.modelURL = $0 }
                if let progress = downloader.progressText {
                    HStack(spacing: 8) {
                        ProgressView().scaleEffect(0.8)
                        Text(progress)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Button {
                    downloadModel()
                } label: {
                    Label(modelInstalled ? "重新下载离线模型" : "下载离线模型",
                          systemImage: "arrow.down.circle")
                }
                .disabled(downloader.isDownloading)
                if let downloadError = downloadError {
                    Text(downloadError)
                        .font(.caption)
                        .foregroundColor(Theme.error)
                }
            } header: {
                Text("离线语音模型")
            } footer: {
                Text("模型约 189MB，下载到本机 Application Support，不占用安装包体积。")
            }
        }
        .navigationTitle("设置")
        .sheet(isPresented: $showEditor) {
            ProfileEditorSheet(profile: editing)
                .environmentObject(store)
        }
        .onAppear { modelInstalled = SpeechOnnx.modelAvailable }
    }

    private func downloadModel() {
        downloadError = nil
        Task {
            do {
                try await downloader.downloadAndInstall()
                modelInstalled = SpeechOnnx.modelAvailable
                if !modelInstalled {
                    downloadError = "解压完成但模型文件不全，请检查压缩包内容"
                }
            } catch {
                downloadError = "下载失败：\(error.localizedDescription)"
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

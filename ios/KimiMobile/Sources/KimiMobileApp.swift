import SwiftUI

@main
struct KimiMobileApp: App {
    @StateObject private var store = ProfileStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .tint(Theme.primary)
        }
    }
}

/// 启动门控：先探测服务器（GateView），通了且无 token 先进设置页，否则进主界面。
/// 切换/编辑主机档案会 bump revision，触发重新探测。
struct RootView: View {
    @EnvironmentObject private var store: ProfileStore
    @State private var connected = false
    @State private var showSettingsForToken = false
    /// 深链打开（kimi-mobile://connect?...）时弹导入弹窗并预填链接
    @State private var showImporter = false
    @State private var importPrefill = ""
    /// 启动自动检查到有更新时弹出提醒（与设置页手动检查共用同一弹窗）
    @State private var updateFound: AppUpdateChecker.Info?

    var body: some View {
        Group {
            if connected {
                MainView()
            } else {
                GateView(onConnected: {
                    if store.token.isEmpty {
                        showSettingsForToken = true
                    } else {
                        connected = true
                    }
                })
                .sheet(isPresented: $showSettingsForToken) {
                    NavigationStack {
                        SettingsView(firstRunHint: true)
                    }
                }
            }
        }
        // 档案变化（切换主机/改地址/改 token）后重新走门控
        .onChange(of: store.revision) { _ in
            connected = false
        }
        .sheet(item: $updateFound) { info in
            UpdateReminderSheet(info: info)
        }
        .sheet(isPresented: $showImporter) {
            ImportServerSheet(prefill: importPrefill)
        }
        .onOpenURL { url in
            guard url.scheme == "kimi-mobile", url.host == "connect" else { return }
            importPrefill = url.absoluteString
            showImporter = true
        }
        .task {
            await autoCheckUpdate()
        }
    }

    /// 启动后静默检查更新（每 24h 一次，时间存 UserDefaults）；失败静默，仅发现新版本时提醒
    private func autoCheckUpdate() async {
        if let last = store.lastUpdateCheck,
           Date().timeIntervalSince(last) < 24 * 3600 { return }
        store.lastUpdateCheck = Date()
        guard case .success(let info) = await AppUpdateChecker.checkLatest(),
              info.updateAvailable else { return }
        updateFound = info
    }
}

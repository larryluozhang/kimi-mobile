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
    }
}

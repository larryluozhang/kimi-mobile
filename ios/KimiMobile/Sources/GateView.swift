import SwiftUI

/// 启动门控：探测服务器 /api/v1/healthz（3s 超时）。
/// 不通则展示引导「请打开 Tailscale 并连接」并每 5s 自动重试。
struct GateView: View {
    @EnvironmentObject private var store: ProfileStore
    let onConnected: () -> Void

    @State private var probing = false
    @State private var failed = false
    @State private var retryTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            gateContent
        }
    }

    private var gateContent: some View {
        VStack(spacing: 20) {
            Spacer()

            // 与 App 图标同款：蓝紫渐变 + 白色气泡 K
            ZStack {
                RoundedRectangle(cornerRadius: 20)
                    .fill(Theme.brandGradient)
                Image(systemName: "bubble.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.white)
                Text("K")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(Theme.primary)
            }
            .frame(width: 84, height: 84)
            .shadow(radius: 6)

            Text("Kimi Mobile")
                .font(.title.bold())

            if let profile = store.activeProfile {
                Text("\(profile.name)\n\(profile.url)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }

            if failed {
                VStack(spacing: 10) {
                    Text("无法连接服务器")
                        .font(.headline)
                        .foregroundColor(Theme.error)
                    Text("请打开 Tailscale 并连接")
                        .font(.body)
                    ProgressView()
                        .padding(.top, 4)
                    Text("每 5 秒自动重试…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding()
                .background(Theme.statusBackground)
                .cornerRadius(12)
                .padding(.horizontal)
            } else {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在连接服务器…")
                        .foregroundColor(.secondary)
                }
            }

            Spacer()

            NavigationLink {
                SettingsView()
            } label: {
                Label("主机设置", systemImage: "gearshape")
            }
            .padding(.bottom, 30)
        }
        .frame(maxWidth: .infinity)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { startProbing() }
        .onDisappear {
            retryTask?.cancel()
            retryTask = nil
        }
    }

    private func startProbing() {
        retryTask?.cancel()
        retryTask = Task {
            while !Task.isCancelled {
                await probe()
                // 成功后立即回调并退出循环
                if !failed { return }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    private func probe() async {
        guard !probing else { return }
        probing = true
        let ok = await APIClient.healthz(server: store.serverURL)
        probing = false
        if Task.isCancelled { return }
        failed = !ok
        if ok { onConnected() }
    }
}

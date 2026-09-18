import SwiftUI

/// 应用更新检查：拉取固定的 latest.json，取 ios 节点按 build 号（Int）与
/// 本机 CFBundleVersion 比较。仅提醒，iOS 无法自动安装。
enum AppUpdateChecker {
    struct Info: Identifiable {
        let updateAvailable: Bool
        let version: String
        let build: Int
        let notes: String

        var id: Int { build }
    }

    private static let manifestURL = URL(string:
        "https://github.com/larryluozhang/kimi-mobile/releases/download/app-latest/latest.json")!

    /// GET latest.json（10 秒超时），解析 ios 对象并与本机 build 比较；
    /// 网络/解析失败返回 .failure，调用方决定提示还是静默
    static func checkLatest() async -> Result<Info, Error> {
        do {
            var req = URLRequest(url: manifestURL, timeoutInterval: 10)
            req.httpMethod = "GET"
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
            guard code == 200 else {
                throw APIError(httpCode: code, message: "HTTP \(code)")
            }
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            guard let ios = obj?["ios"] as? [String: Any],
                  let build = (ios["build"] as? NSNumber)?.intValue else {
                throw APIError(httpCode: code, message: "更新信息格式不正确")
            }
            let current = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
            return .success(Info(updateAvailable: build > current,
                                 version: ios["version"] as? String ?? "",
                                 build: build,
                                 notes: ios["notes"] as? String ?? ""))
        } catch {
            return .failure(error)
        }
    }
}

/// 更新提醒弹窗：设置页手动检查与启动自动检查共用。
struct UpdateReminderSheet: View {
    let info: AppUpdateChecker.Info
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(info.notes.isEmpty ? "（无更新说明）" : info.notes)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("iOS 无法自动安装，请联系管理员或用 Xcode 更新。")
                        .font(.callout)
                        .foregroundColor(.secondary)
                }
                .padding()
            }
            .navigationTitle("发现新版本 \(info.version)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("知道了") { dismiss() }
                }
            }
        }
    }
}

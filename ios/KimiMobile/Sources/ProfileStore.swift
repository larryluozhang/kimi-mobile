import Foundation
import Combine

/// 主机档案存储：列表/选中项/杂项偏好存 UserDefaults，token 存 Keychain。
/// 首次运行播种两条预置档案（token 留空，由用户在设置页填写）。
@MainActor
final class ProfileStore: ObservableObject {
    @Published private(set) var profiles: [HostProfile] = []
    @Published var activeProfileId: String? {
        didSet { defaults.set(activeProfileId, forKey: Keys.active) }
    }
    /// 档案内容（名称/URL/token）变化时 +1，供 GateView 重新探测
    @Published private(set) var revision = 0

    @Published var voiceEnabled: Bool {
        didSet { defaults.set(voiceEnabled, forKey: Keys.voice) }
    }
    /// 语音识别引擎：auto（默认，离线模型可用则优先）/ onnx（仅离线）/ system（仅系统识别）
    @Published var voiceEngine: String {
        didSet { defaults.set(voiceEngine, forKey: Keys.voiceEngine) }
    }
    @Published var model: String {
        didSet { defaults.set(model, forKey: Keys.model) }
    }
    var lastWorkspaceId: String? {
        get { defaults.string(forKey: Keys.workspace) }
        set { defaults.set(newValue, forKey: Keys.workspace) }
    }

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let profiles = "host_profiles"
        static let active = "active_profile_id"
        static let voice = "voice_enabled"
        static let voiceEngine = "voice_engine"
        static let model = "model"
        static let workspace = "last_workspace_id"
        static let seeded = "seeded_v3"
    }

    static let presets: [HostProfile] = [
        HostProfile(id: "preset-146", name: "我的服务器", url: "http://127.0.0.1:58627"),
        HostProfile(id: "preset-mac", name: "我的 Mac", url: "http://127.0.0.1:58627"),
        HostProfile(id: "preset-beelink", name: "备用主机", url: "http://127.0.0.1:58627")
    ]

    init() {
        let defaults = UserDefaults.standard
        if !defaults.bool(forKey: Keys.seeded) {
            defaults.set(true, forKey: Keys.seeded)
            // 合并播种：只补充缺失的预置档案，保留用户已有/改过的档案
            var existing: [HostProfile] = []
            if let data = defaults.data(forKey: Keys.profiles),
               let list = try? JSONDecoder().decode([HostProfile].self, from: data) {
                existing = list
            }
            let missing = Self.presets.filter { p in !existing.contains { $0.id == p.id } }
            if let data = try? JSONEncoder().encode(existing + missing) {
                defaults.set(data, forKey: Keys.profiles)
            }
        }
        var loaded: [HostProfile] = []
        if let data = defaults.data(forKey: Keys.profiles),
           let list = try? JSONDecoder().decode([HostProfile].self, from: data) {
            loaded = list
        }
        profiles = loaded
        activeProfileId = defaults.string(forKey: Keys.active) ?? loaded.first?.id
        voiceEnabled = defaults.object(forKey: Keys.voice) as? Bool ?? true
        let engine = defaults.string(forKey: Keys.voiceEngine) ?? "auto"
        voiceEngine = ["auto", "onnx", "system"].contains(engine) ? engine : "auto"
        let m = defaults.string(forKey: Keys.model) ?? ""
        model = m.trimmingCharacters(in: .whitespaces).isEmpty ? Constants.defaultModel : m
    }

    var activeProfile: HostProfile? {
        profiles.first { $0.id == activeProfileId } ?? profiles.first
    }

    var serverURL: String {
        var u = (activeProfile?.url ?? "").trimmingCharacters(in: .whitespaces)
        while u.hasSuffix("/") { u.removeLast() }
        return u
    }

    var token: String {
        guard let id = activeProfile?.id else { return "" }
        return KeychainStore.token(for: id).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func token(for profile: HostProfile) -> String {
        KeychainStore.token(for: profile.id)
    }

    func upsert(_ profile: HostProfile, token: String) {
        var list = profiles
        if let idx = list.firstIndex(where: { $0.id == profile.id }) {
            list[idx] = profile
        } else {
            list.append(profile)
        }
        saveProfiles(list)
        profiles = list
        KeychainStore.setToken(token, for: profile.id)
        if activeProfileId == nil { activeProfileId = profile.id }
        revision += 1
    }

    func delete(_ profile: HostProfile) {
        let list = profiles.filter { $0.id != profile.id }
        saveProfiles(list)
        profiles = list
        KeychainStore.deleteToken(for: profile.id)
        if activeProfileId == profile.id {
            activeProfileId = list.first?.id
        }
        revision += 1
    }

    func setActive(_ profile: HostProfile) {
        activeProfileId = profile.id
        revision += 1
    }

    // MARK: - 持久化

    private func saveProfiles(_ list: [HostProfile]) {
        if let data = try? JSONEncoder().encode(list) {
            defaults.set(data, forKey: Keys.profiles)
        }
    }
}

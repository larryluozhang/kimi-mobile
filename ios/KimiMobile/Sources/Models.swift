import Foundation

/// 主机档案：token 不存这里，放 Keychain（account = id）
struct HostProfile: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var url: String
}

struct SessionItem: Identifiable, Equatable {
    let id: String
    let title: String
    let updatedAt: String
    let busy: Bool
    let workspaceId: String
}

struct WorkspaceItem: Identifiable, Equatable {
    let id: String
    let name: String
    let root: String
}

struct ChatMessage: Identifiable, Equatable {
    let id: String
    let role: String // "user" / "assistant" / "tool"
    var text: String
    var isError: Bool = false
    var isStreaming: Bool = false
    /// 排队中的用户消息（busy 时服务端 status="queued"，轮到执行前不进历史）
    var isQueued: Bool = false
    /// 执行中：文本与 GET /prompts?status=queued 的 data.active 匹配
    /// （v0.37.2：active 是当前正在执行的 prompt，不在 queued[] 里），UI 显示"执行中"而非"排队中"
    var isExecuting: Bool = false
    /// 未送达：pendingLocal 超过 60s 仍既不在历史也不在服务端队列，
    /// 判定被服务端丢弃（上游 bug #3127 幻影 busy 吞排队 prompt），UI 红色警示
    var deliveryFailed: Bool = false
    var createdAt: Date? = nil
    /// 工具活动条目（role == "tool"）：工具名与完成标记，turn 结束历史刷新时随列表替换消失
    var toolName: String = ""
    var toolDone: Bool = false
}

/// 待审批项（GET /sessions/{id}/approvals?status=pending 的 data.items[] 元素）
struct ApprovalItem: Identifiable, Equatable {
    let id: String // approval_id
    let toolName: String
    let action: String
    let summary: String // tool_input_display.summary
}

/// 待答问卷（GET /sessions/{id}/questions?status=pending 的 data.items[] 元素）
struct QuestionItem: Identifiable, Equatable {
    let id: String // question_id
    let questions: [Question]
}

/// 问卷内单个问题
struct Question: Identifiable, Equatable {
    let id: String // 问题 id（如 q_0），回答 body 的 answers 键
    let question: String
    let header: String
    let options: [QuestionOption]
    let allowOther: Bool // 是否允许"其他"文本输入
}

struct QuestionOption: Identifiable, Equatable {
    let id: String
    let label: String
    let description: String
}

enum Constants {
    static let defaultModel = "kimi-code/k3"
    static let defaultWorkspaceRoot = "/tmp/kimi-workspace"
    /// 可选模型（与服务端目录一致）
    static let availableModels = [
        "kimi-code/k3",
        "kimi-code/kimi-for-coding",
        "kimi-code/kimi-for-coding-highspeed",
        "kimi-code/k3-256k"
    ]
}

/// 会话 agent 配置（GET/POST /sessions/{id}/profile 的 agent_config 字段）
struct AgentConfig: Equatable {
    var model: String = ""
    var planMode: Bool = false
    var swarmMode: Bool = false
    var permissionMode: String = "manual" // manual / yolo / auto
    var thinking: String = ""
    var goalObjective: String = ""

    static func from(json: [String: Any]) -> AgentConfig {
        AgentConfig(model: json["model"] as? String ?? "",
                    planMode: json["plan_mode"] as? Bool ?? false,
                    swarmMode: json["swarm_mode"] as? Bool ?? false,
                    permissionMode: json["permission_mode"] as? String ?? "manual",
                    thinking: json["thinking"] as? String ?? "",
                    goalObjective: json["goal_objective"] as? String ?? "")
    }

    /// 序列化为 UserDefaults 可存的 JSON 字典（本地持久化用）
    func toDict() -> [String: Any] {
        ["model": model,
         "plan_mode": planMode,
         "swarm_mode": swarmMode,
         "permission_mode": permissionMode,
         "thinking": thinking,
         "goal_objective": goalObjective]
    }

    /// POST /prompts 顶层随带的模式字段（只带非默认/已设置的；model 由调用方单独处理）
    var promptFields: [String: Any] {
        var d: [String: Any] = [:]
        if planMode { d["plan_mode"] = true }
        if swarmMode { d["swarm_mode"] = true }
        if permissionMode != "manual" { d["permission_mode"] = permissionMode }
        if !thinking.isEmpty { d["thinking"] = thinking }
        if !goalObjective.isEmpty { d["goal_objective"] = goalObjective }
        return d
    }
}

/// 会话模式本地持久化：UserDefaults 存 JSON（sessionId -> 模式集合）。
/// 服务端 GET /profile 的 agent_config 是硬编码空壳（v0.35.0 确认），
/// 模式状态以本地记录为准，每条 prompt 随带驱动 turn 行为。
enum SessionModeStore {
    private static let key = "session_modes_v1"

    static func load(sessionId: String) -> AgentConfig? {
        guard let all = UserDefaults.standard.dictionary(forKey: key),
              let raw = all[sessionId] as? [String: Any] else { return nil }
        return AgentConfig.from(json: raw)
    }

    static func save(sessionId: String, config: AgentConfig) {
        var all = UserDefaults.standard.dictionary(forKey: key) ?? [:]
        all[sessionId] = config.toDict()
        UserDefaults.standard.set(all, forKey: key)
    }
}

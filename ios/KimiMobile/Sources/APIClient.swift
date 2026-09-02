import Foundation

/// REST 错误。服务端可能把业务错误包在 HTTP 200 里（{"code":4xxxx,"msg":...}），统一在这里解析。
struct APIError: LocalizedError {
    let httpCode: Int
    let message: String

    var isAuthError: Bool { httpCode == 401 || httpCode == 403 }

    var errorDescription: String? { message }
}

enum APIClient {
    /// healthz 探测（Gate 用），3 秒超时；返回是否 200
    static func healthz(server: String) async -> Bool {
        guard let url = URL(string: server + "/api/v1/healthz") else { return false }
        var req = URLRequest(url: url, timeoutInterval: 3)
        req.httpMethod = "GET"
        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            return (resp as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    // MARK: - 内部

    private static func request(server: String, token: String, path: String,
                                method: String = "GET", body: [String: Any]? = nil) throws -> URLRequest {
        guard let url = URL(string: server + path) else {
            throw APIError(httpCode: 0, message: "服务器地址无效：\(server)")
        }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body = body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    /// 解包 {"code":0,"data":...} 信封；code 非 0 视为业务错误
    private static func unwrap(_ data: Data, _ resp: URLResponse) throws -> [String: Any] {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let text = String(data: data, encoding: .utf8) ?? ""
        if code == 401 || code == 403 {
            throw APIError(httpCode: code, message: "Token 无效或已过期（HTTP \(code)）")
        }
        guard code == 200 else {
            throw APIError(httpCode: code, message: "HTTP \(code): \(text.prefix(200))")
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if let code0 = obj["code"] as? Int, code0 != 0 {
            let msg = obj["msg"] as? String ?? "服务器返回错误 code=\(code0)"
            throw APIError(httpCode: 200, message: msg)
        }
        return obj["data"] as? [String: Any] ?? [:]
    }

    // MARK: - 业务接口

    static func listWorkspaces(server: String, token: String) async throws -> [WorkspaceItem] {
        let req = try request(server: server, token: token, path: "/api/v1/workspaces")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        let items = d["items"] as? [[String: Any]] ?? []
        return items.map { w in
            let root = w["root"] as? String ?? ""
            let name = (w["name"] as? String ?? "")
            return WorkspaceItem(id: w["id"] as? String ?? "",
                                 name: name.isEmpty ? root : name,
                                 root: root)
        }
    }

    static func listSessions(server: String, token: String) async throws -> [SessionItem] {
        // 三个布尔查询参数必填（服务端要求）。
        // 注意：不能带 busy=false，否则正在运行/卡审批的会话会被过滤掉（用户视角"会话丢失"）
        let path = "/api/v1/sessions?page_size=50&include_archive=false&exclude_empty=false&archived_only=false"
        let req = try request(server: server, token: token, path: path)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        let items = d["items"] as? [[String: Any]] ?? []
        return items.map { s in
            var title = s["title"] as? String ?? ""
            if title.isEmpty { title = s["last_prompt"] as? String ?? "" }
            if title.isEmpty { title = "（未命名会话）" }
            return SessionItem(id: s["id"] as? String ?? "",
                               title: title,
                               updatedAt: s["updated_at"] as? String ?? "",
                               busy: s["busy"] as? Bool ?? false,
                               workspaceId: s["workspace_id"] as? String ?? "")
        }
    }

    static func createSession(server: String, token: String, workspace: WorkspaceItem) async throws -> SessionItem {
        let body: [String: Any] = [
            "metadata": ["cwd": workspace.root],
            "workspace_id": workspace.id
        ]
        let req = try request(server: server, token: token, path: "/api/v1/sessions",
                              method: "POST", body: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        return SessionItem(id: d["id"] as? String ?? "",
                           title: (d["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "（新会话）",
                           updatedAt: d["updated_at"] as? String ?? "",
                           busy: false,
                           workspaceId: d["workspace_id"] as? String ?? workspace.id)
    }

    static func getMessages(server: String, token: String, sessionId: String) async throws -> [ChatMessage] {
        let path = "/api/v1/sessions/\(sessionId)/messages?page_size=100"
        let req = try request(server: server, token: token, path: path)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        let items = d["items"] as? [[String: Any]] ?? []
        var out: [ChatMessage] = []
        for m in items {
            let role = m["role"] as? String ?? ""
            guard role == "user" || role == "assistant" else { continue }
            guard let content = m["content"] as? [[String: Any]] else { continue }
            var parts: [String] = []
            for block in content {
                // 只渲染 text 块；thinking 等其余块不进入正文
                guard block["type"] as? String == "text" else { continue }
                let text = block["text"] as? String ?? ""
                // 隐藏系统注入的用户消息块（<system-reminder>/<cron-fire>），与官方 web UI 一致
                if role == "user" && isSystemInjected(text) { continue }
                if !text.isEmpty { parts.append(text) }
            }
            // 所有块都被过滤的消息整条不显示
            guard !parts.isEmpty else { continue }
            out.append(ChatMessage(id: m["id"] as? String ?? UUID().uuidString,
                                   role: role,
                                   text: parts.joined(separator: "\n"),
                                   createdAt: parseISO(m["created_at"] as? String ?? "")))
        }
        return out
    }

    /// prompt 队列条目：text + created_at（服务端时间戳，调和后按时间排序用）
    struct QueuedPrompt {
        let text: String
        let createdAt: Date?
    }

    /// 拉服务端 prompt 队列（GET /sessions/{id}/prompts?status=queued）：
    /// 会话 busy 时 POST /prompts 返回 queued，排队消息在轮到执行前不进 GET /messages 历史，
    /// 以此接口为排队消息的真相来源（重进会话/重启 app 后仍能恢复显示）。
    /// v0.37.2 实测：data.active 是当前正在执行的 prompt（不在 queued[] 里），
    /// 故返回 (active, queued 列表)；文本均为 text 块拼接，系统注入块过滤（同 getMessages）。
    static func listQueuedPrompts(server: String, token: String, sessionId: String) async throws -> (active: QueuedPrompt?, queued: [QueuedPrompt]) {
        let path = "/api/v1/sessions/\(sessionId)/prompts?status=queued"
        let req = try request(server: server, token: token, path: path)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        var active: QueuedPrompt? = nil
        if let a = d["active"] as? [String: Any] {
            let text = promptText(a)
            if !text.isEmpty {
                active = QueuedPrompt(text: text, createdAt: parseISO(a["created_at"] as? String ?? ""))
            }
        }
        let items = d["queued"] as? [[String: Any]] ?? []
        let queued = items.compactMap { p -> QueuedPrompt? in
            let text = promptText(p)
            return text.isEmpty ? nil : QueuedPrompt(text: text, createdAt: parseISO(p["created_at"] as? String ?? ""))
        }
        return (active, queued)
    }

    /// 从 prompt 对象提取文本：text 块拼接，系统注入块过滤（与 getMessages 一致）
    private static func promptText(_ p: [String: Any]) -> String {
        guard let content = p["content"] as? [[String: Any]] else { return "" }
        var parts: [String] = []
        for block in content {
            guard block["type"] as? String == "text" else { continue }
            let text = block["text"] as? String ?? ""
            if isSystemInjected(text) { continue }
            if !text.isEmpty { parts.append(text) }
        }
        return parts.joined(separator: "\n")
    }

    /// 发送 prompt；返回 data.status（"running" | "queued"）。
    /// 会话 busy 时服务端排队（status="queued"），该消息在轮到执行前不会出现在 GET /messages 历史里，
    /// 调用方需保留本地乐观回显直到历史刷新确认。
    static func sendPrompt(server: String, token: String, sessionId: String,
                           text: String, model: String,
                           modeFields: [String: Any] = [:]) async throws -> String {
        // body 顶层必须带 model 字段；模式字段（plan_mode/swarm_mode/permission_mode/
        // thinking/goal_objective）顶层随带以驱动 turn 行为（官方 web UI 同款机制）
        var body: [String: Any] = [
            "content": [["type": "text", "text": text]],
            "model": model
        ]
        for (k, v) in modeFields { body[k] = v }
        let req = try request(server: server, token: token,
                              path: "/api/v1/sessions/\(sessionId)/prompts",
                              method: "POST", body: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        return d["status"] as? String ?? "running"
    }

    // MARK: - 审批 / 问答 / 中断

    /// 轮询待审批项（GET /sessions/{id}/approvals?status=pending）→ data.items[]（approval_id/tool_name/action/tool_input_display.summary）
    static func listPendingApprovals(server: String, token: String, sessionId: String) async throws -> [ApprovalItem] {
        let path = "/api/v1/sessions/\(sessionId)/approvals?status=pending"
        let req = try request(server: server, token: token, path: path)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        let items = d["items"] as? [[String: Any]] ?? []
        return items.map { a in
            let display = a["tool_input_display"] as? [String: Any] ?? [:]
            return ApprovalItem(id: a["approval_id"] as? String ?? "",
                              toolName: a["tool_name"] as? String ?? "",
                              action: a["action"] as? String ?? "",
                              summary: display["summary"] as? String ?? "")
        }.filter { !$0.id.isEmpty }
    }

    /// 响应审批：decision 为 approved / rejected
    static func respondApproval(server: String, token: String, sessionId: String, approvalId: String, decision: String) async throws {
        let req = try request(server: server, token: token,
                          path: "/api/v1/sessions/\(sessionId)/approvals/\(approvalId)",
                          method: "POST", body: ["decision": decision])
        let (data, resp) = try await URLSession.shared.data(for: req)
        _ = try unwrap(data, resp)
    }

    /// 轮询待答问卷（GET /sessions/{id}/questions?status=pending）
    /// → data.items[] 含 question_id、questions[]（每题 id/question/header/options/allow_other）
    static func listPendingQuestions(server: String, token: String, sessionId: String) async throws -> [QuestionItem] {
        let path = "/api/v1/sessions/\(sessionId)/questions?status=pending"
        let req = try request(server: server, token: token, path: path)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        let items = d["items"] as? [[String: Any]] ?? []
        return items.map { item -> QuestionItem in
            let qs = item["questions"] as? [[String: Any]] ?? []
            let questions = qs.map { q -> Question in
                let os = q["options"] as? [[String: Any]] ?? []
                let options = os.map { o in
                    QuestionOption(id: o["id"] as? String ?? "",
                                 label: o["label"] as? String ?? "",
                                 description: o["description"] as? String ?? "")
                }
                return Question(id: q["id"] as? String ?? "",
                              question: q["question"] as? String ?? "",
                              header: q["header"] as? String ?? "",
                              options: options,
                              allowOther: q["allow_other"] as? Bool ?? false)
            }
            return QuestionItem(id: item["question_id"] as? String ?? "",
                              questions: questions)
        }.filter { !$0.id.isEmpty }
    }

    /// 回答问卷。answers 是 answers 记录：{"<问题id>":{"kind":"single","option_id":"..."} 或 {"kind":"other","text":"..."}
    static func answerQuestion(server: String, token: String, sessionId: String, questionId: String,
                              answers: [String: Any]) async throws {
        let req = try request(server: server, token: token,
                              path: "/api/v1/sessions/\(sessionId)/questions/\(questionId)",
                              method: "POST", body: ["answers": answers])
        let (data, resp) = try await URLSession.shared.data(for: req)
        _ = try unwrap(data, resp)
    }

    /// 中断当前 turn（POST /sessions/{id}:abort，冒号后缀语法，实测返回 {"aborted":true}）
    static func abortSession(server: String, token: String, sessionId: String) async throws {
        let req = try request(server: server, token: token,
                              path: "/api/v1/sessions/\(sessionId):abort",
                              method: "POST")
        let (data, resp) = try await URLSession.shared.data(for: req)
        _ = try unwrap(data, resp)
    }

    // MARK: - 会话 profile（模式栏）

    /// 读会话 profile。注意：v0.35.0 服务端 toWireSession 硬编码 agent_config:{model:""}，
    /// 不回传真实模式状态 —— 仅作本地无记录时的兜底，结果以调用方判断为准。
    static func getProfile(server: String, token: String, sessionId: String) async throws -> AgentConfig {
        let req = try request(server: server, token: token,
                              path: "/api/v1/sessions/\(sessionId)/profile")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let d = try unwrap(data, resp)
        return AgentConfig.from(json: d["agent_config"] as? [String: Any] ?? [:])
    }

    /// 增量更新 agent_config；fields 只带要改的键（服务端合并）。
    /// goal_control 仅作为控制命令（pause/resume/cancel），不回显。
    static func updateProfile(server: String, token: String, sessionId: String,
                              fields: [String: Any]) async throws {
        let body: [String: Any] = ["agent_config": fields]
        let req = try request(server: server, token: token,
                              path: "/api/v1/sessions/\(sessionId)/profile",
                              method: "POST", body: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        _ = try unwrap(data, resp)
    }

    // MARK: - 工具

    /// 系统注入内容特征：以 <system-reminder>、<cron-fire 或 <notification 开头（允许前导空白）
    static func isSystemInjected(_ text: String) -> Bool {
        let t = String(text.drop(while: { $0 == " " || $0 == "\t" || $0 == "\n" }))
        return t.hasPrefix("<system-reminder>") || t.hasPrefix("<cron-fire") || t.hasPrefix("<notification")
    }

    static func parseISO(_ s: String) -> Date? {
        guard !s.isEmpty else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

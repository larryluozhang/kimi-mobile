import Foundation

/// WS 事件，回调给 ChatViewModel（已在主线程）
enum WSEvent {
    case open
    case closed               // 连接断开（会自动重连）
    case authError
    case error(String)
    case workChanged(Bool)
    /// agent phase：kind = running/tool_call/streaming/ended/interrupted；stream = thinking/text
    /// （transcript.ops 的 meta.merge 与 transcript.reset 快照 meta.agent.phase 都会发）
    case phase(kind: String, stream: String)
    case frameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String)
    /// 工具调用帧（frame.kind == "tool"）：state = running/done，summary 为单行摘要
    case toolUpsert(frameId: String, name: String, state: String, summary: String)
    case frameAppend(frameId: String, offset: Int64, text: String)
    case turnState(state: String, error: String?)
    case transcriptReset
}

/**
 * Kimi Code daemon WebSocket 客户端（URLSessionWebSocketTask 实现）。
 *
 * 协议（与 Android/macOS 版实测一致）：
 *  - 握手 HTTP 头带 Authorization: Bearer <token>
 *  - 连上后发 client_hello；收到 ack 后发 subscribe_v2（transcript 为 {"*":"delta"}）
 *  - 流式内容通过 transcript.ops 帧下发（frame.upsert / append / meta.merge / turn.upsert …）
 *  - 服务端每 10s 发 JSON ping，必须回 pong（同 nonce）
 *  - 重订阅/重连会先收 transcript.reset，应丢弃本地流式缓冲
 */
final class WSService: NSObject {
    var onEvent: ((WSEvent) -> Void)?

    private let serverHTTP: String
    private let token: String
    private let sessionId: String

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var stopped = false
    private var reconnectDelay: TimeInterval = 1
    private var helloId: String?
    private var subId: String?

    init(serverHTTP: String, token: String, sessionId: String) {
        self.serverHTTP = serverHTTP
        self.token = token
        self.sessionId = sessionId
    }

    func start() {
        stopped = false
        connect()
    }

    func stop() {
        stopped = true
        reconnectTask?.cancel()
        receiveTask?.cancel()
        task?.cancel(with: .normalClosure, reason: Data("bye".utf8))
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    private func wsURL() -> URL? {
        var u = serverHTTP.trimmingCharacters(in: .whitespaces)
        while u.hasSuffix("/") { u.removeLast() }
        if u.hasPrefix("https://") {
            u = "wss://" + u.dropFirst("https://".count)
        } else if u.hasPrefix("http://") {
            u = "ws://" + u.dropFirst("http://".count)
        } else {
            u = "ws://" + u
        }
        return URL(string: u + "/api/v1/ws")
    }

    private func connect() {
        guard !stopped, let url = wsURL() else { return }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let s = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        session = s
        let t = s.webSocketTask(with: req)
        task = t
        t.resume()
        receiveLoop(t)
    }

    private func receiveLoop(_ t: URLSessionWebSocketTask) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self = self else { return }
            while !Task.isCancelled && !self.stopped {
                do {
                    let msg = try await t.receive()
                    switch msg {
                    case .string(let text):
                        self.handle(text)
                    case .data(let data):
                        if let text = String(data: data, encoding: .utf8) {
                            self.handle(text)
                        }
                    @unknown default:
                        break
                    }
                } catch {
                    // receive 抛错 = 连接已断；URLSession 代理回调拿不到时兜底重连
                    if !self.stopped { self.scheduleReconnect() }
                    return
                }
            }
        }
    }

    private func scheduleReconnect() {
        guard !stopped else { return }
        emit(.closed)
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, 30)
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self = self, !Task.isCancelled, !self.stopped else { return }
            self.connect()
        }
    }

    private func emit(_ e: WSEvent) {
        let cb = onEvent
        DispatchQueue.main.async { cb?(e) }
    }

    private func send(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    // MARK: - 协议处理

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = msg["type"] as? String else { return }

        switch type {
        case "ping":
            guard let p = msg["payload"] as? [String: Any],
                  let nonce = p["nonce"] as? String else { return }
            send(["type": "pong", "payload": ["nonce": nonce]])

        case "ack":
            let id = msg["id"] as? String ?? ""
            if let code = msg["code"] as? Int, code != 0 {
                emit(.error(msg["msg"] as? String ?? "服务器拒绝请求"))
                return
            }
            if id == helloId {
                let sid = "s-" + UUID().uuidString
                subId = sid
                send([
                    "type": "subscribe_v2",
                    "id": sid,
                    "payload": [
                        "session_id": sessionId,
                        "transcript": ["*": "delta"]
                    ]
                ])
            } else if id == subId {
                emit(.open)
            }

        case "error":
            let p = msg["payload"] as? [String: Any]
            let errMsg = p?["msg"] as? String ?? "WebSocket 错误"
            if (p?["fatal"] as? Bool) == true {
                emit(.error(errMsg))
            }

        case "event.session.work_changed":
            guard let p = msg["payload"] as? [String: Any] else { return }
            emit(.workChanged(p["busy"] as? Bool ?? false))

        case "transcript.reset":
            emit(.transcriptReset)
            // v0.37.2：turn 进行中才订阅的 WS 收不到该 turn 的 transcript.ops，
            // 但 reset 的 payload.snapshot.meta.agent.phase 带实时阶段，据此补发 .phase。
            // 多 agent 场景会收到多个 reset，只取 main agent（agent_id=="main" 或 meta 非空）
            let p = msg["payload"] as? [String: Any] ?? [:]
            let agentId = p["agent_id"] as? String ?? ""
            let meta = (p["snapshot"] as? [String: Any])?["meta"] as? [String: Any]
            let isMain = agentId.isEmpty || agentId == "main" || (meta?.isEmpty == false)
            if isMain,
               let phase = (meta?["agent"] as? [String: Any])?["phase"] as? [String: Any] {
                emit(.phase(kind: phase["kind"] as? String ?? "",
                            stream: phase["stream"] as? String ?? ""))
            }

        case "transcript.ops":
            guard let p = msg["payload"] as? [String: Any],
                  let ops = p["ops"] as? [[String: Any]] else { return }
            dispatchOps(ops)

        default:
            break
        }
    }

    private func dispatchOps(_ ops: [[String: Any]]) {
        for op in ops {
            switch op["op"] as? String {
            case "frame.upsert":
                guard let frame = op["frame"] as? [String: Any] else { continue }
                let kind = frame["kind"] as? String ?? ""
                if kind == "tool" {
                    emit(.toolUpsert(frameId: frame["frameId"] as? String ?? "",
                                     name: frame["name"] as? String ?? "",
                                     state: frame["state"] as? String ?? "",
                                     summary: toolSummary(frame)))
                    continue
                }
                emit(.frameUpsert(turnId: op["turnId"] as? String ?? "",
                                  frameId: frame["frameId"] as? String ?? "",
                                  kind: kind,
                                  role: frame["role"] as? String ?? "",
                                  text: frame["text"] as? String ?? ""))
            case "append":
                guard let target = op["target"] as? [String: Any],
                      target["type"] as? String == "frame" else { continue }
                let offset = (op["offset"] as? NSNumber)?.int64Value ?? 0
                emit(.frameAppend(frameId: target["frameId"] as? String ?? "",
                                  offset: offset,
                                  text: op["text"] as? String ?? ""))
            case "meta.merge":
                guard let meta = op["meta"] as? [String: Any],
                      let agent = meta["agent"] as? [String: Any],
                      let phase = agent["phase"] as? [String: Any] else { continue }
                emit(.phase(kind: phase["kind"] as? String ?? "",
                            stream: phase["stream"] as? String ?? ""))
            case "turn.upsert":
                guard let turn = op["turn"] as? [String: Any] else { continue }
                emit(.turnState(state: turn["state"] as? String ?? "",
                                error: turn["error"] as? String))
            default:
                continue
            }
        }
    }

    /// 工具帧单行摘要：display.summary ?: inputText ?: input 描述；去换行截断 ~80 字符
    private func toolSummary(_ frame: [String: Any]) -> String {
        var raw = ""
        if let display = frame["display"] as? [String: Any],
           let s = display["summary"] as? String, !s.isEmpty {
            raw = s
        } else if let s = frame["inputText"] as? String, !s.isEmpty {
            raw = s
        } else if let input = frame["input"] {
            if let d = try? JSONSerialization.data(withJSONObject: input),
               let s = String(data: d, encoding: .utf8) {
                raw = s
            }
        }
        let flat = raw.components(separatedBy: .newlines).joined(separator: " ")
        return flat.count > 80 ? String(flat.prefix(80)) + "…" : flat
    }
}

// MARK: - URLSessionWebSocketDelegate

extension WSService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        reconnectDelay = 1
        let hid = "h-" + UUID().uuidString
        helloId = hid
        send(["type": "client_hello", "id": hid, "payload": ["client_id": "kimi-mobile-ios"]])
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        scheduleReconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        guard let error = error, !stopped else { return }
        // 握手阶段 401/403：token 无效，不再重连
        if let resp = task.response as? HTTPURLResponse,
           resp.statusCode == 401 || resp.statusCode == 403 {
            stopped = true
            emit(.authError)
            return
        }
        scheduleReconnect()
        _ = error
    }
}

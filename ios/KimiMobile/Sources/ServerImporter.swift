import Foundation

/// 导入服务器：解析 kimi-mobile://connect 深链或等价的 JSON 卡片 {"name","url","token"}。
enum ServerImporter {
    struct Payload {
        var name: String
        var url: String
        var token: String
    }

    enum ImportError: LocalizedError {
        case empty
        case unrecognized
        case invalidDeeplink
        case invalidJSON
        case missingField(String)
        case invalidURL

        var errorDescription: String? {
            switch self {
            case .empty: return "内容为空，请粘贴链接或 JSON 卡片"
            case .unrecognized: return "无法识别：请粘贴 kimi-mobile://connect 链接或 JSON 卡片"
            case .invalidDeeplink: return "深链不合法：无法解析 query 参数"
            case .invalidJSON: return "JSON 不合法：应为 {\"name\",\"url\",\"token\"} 结构"
            case .missingField(let f): return "缺少字段：\(f)"
            case .invalidURL: return "URL 不合法：需以 http:// 或 https:// 开头并包含主机名"
            }
        }
    }

    /// 接受两种形式（整体先 trim）：kimi-mobile://connect 深链 / JSON 卡片
    static func parse(_ raw: String) -> Result<Payload, ImportError> {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .failure(.empty) }
        if text.hasPrefix("kimi-mobile://connect") { return parseDeeplink(text) }
        if text.hasPrefix("{") { return parseJSON(text) }
        return .failure(.unrecognized)
    }

    private static func parseDeeplink(_ text: String) -> Result<Payload, ImportError> {
        guard let comps = URLComponents(string: text) else { return .failure(.invalidDeeplink) }
        // v 可选，忽略；queryItems 已做 percent-decoding
        var fields: [String: String] = [:]
        for item in comps.queryItems ?? [] {
            if let value = item.value { fields[item.name] = value }
        }
        return build(from: fields)
    }

    private static func parseJSON(_ text: String) -> Result<Payload, ImportError> {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONDecoder().decode([String: String].self, from: data) else {
            return .failure(.invalidJSON)
        }
        return build(from: obj)
    }

    private static func build(from fields: [String: String]) -> Result<Payload, ImportError> {
        guard let urlRaw = fields["url"],
              !urlRaw.trimmingCharacters(in: .whitespaces).isEmpty else {
            return .failure(.missingField("url"))
        }
        guard let token = fields["token"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty else {
            return .failure(.missingField("token"))
        }
        var url = urlRaw.trimmingCharacters(in: .whitespaces)
        while url.hasSuffix("/") { url.removeLast() }
        guard url.hasPrefix("http://") || url.hasPrefix("https://"),
              let parsed = URL(string: url),
              let host = parsed.host, !host.isEmpty else {
            return .failure(.invalidURL)
        }
        // name 为空时默认取 URL 的 host
        let name = fields["name"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return .success(Payload(name: name.isEmpty ? host : name, url: url, token: token))
    }
}

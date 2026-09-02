import SwiftUI

/// 轻量 Markdown 渲染：按 ``` 围栏拆成 普通文本 / 代码块。
/// 代码块用等宽字体 + 深色底，可横向滚动；普通文本原样显示。
struct MarkdownContent: View {
    let text: String

    private enum Segment: Equatable {
        case text(String)
        case code(lang: String, String)
    }

    private var segments: [Segment] { Self.parse(text) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                switch seg {
                case .text(let s):
                    if !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(s.trimmingCharacters(in: .newlines))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                case .code(_, let code):
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(code.trimmingCharacters(in: .newlines))
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundColor(Theme.codeText)
                            .padding(10)
                    }
                    .background(Theme.codeBackground)
                    .cornerRadius(8)
                    .contextMenu {
                        Button {
                            UIPasteboard.general.string = code.trimmingCharacters(in: .newlines)
                        } label: {
                            Label("复制代码", systemImage: "doc.on.doc")
                        }
                    }
                }
            }
        }
    }

    private static func parse(_ text: String) -> [Segment] {
        var out: [Segment] = []
        var rest = Substring(text)
        while let range = rest.range(of: "```") {
            let before = rest[..<range.lowerBound]
            if !before.isEmpty { out.append(.text(String(before))) }
            var after = rest[range.upperBound...]
            // 语言标识行
            var lang = ""
            if let nl = after.firstIndex(of: "\n") {
                lang = String(after[..<nl]).trimmingCharacters(in: .whitespaces)
                after = after[after.index(after: nl)...]
            }
            if let close = after.range(of: "```") {
                out.append(.code(lang: lang, String(after[..<close.lowerBound])))
                rest = after[close.upperBound...]
            } else {
                // 未闭合围栏：剩余全部当代码（流式输出中常见）
                out.append(.code(lang: lang, String(after)))
                rest = ""
            }
        }
        if !rest.isEmpty { out.append(.text(String(rest))) }
        return out
    }
}

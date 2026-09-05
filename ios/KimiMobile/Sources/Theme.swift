import SwiftUI

/// Kimi 品牌色与聊天配色（与 Android/macOS 版保持一致），深浅色自适应。
enum Theme {
    static let brandBlue = Color(red: 0x3D / 255, green: 0x7B / 255, blue: 0xFA / 255)
    static let brandPurple = Color(red: 0x8B / 255, green: 0x5C / 255, blue: 0xF6 / 255)
    static let primary = Color(red: 0x5A / 255, green: 0x63 / 255, blue: 0xE8 / 255)

    static var brandGradient: LinearGradient {
        LinearGradient(colors: [brandBlue, brandPurple],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// 用户气泡渐变（#4E7BF5 -> #7B5CF0）
    static var userBubbleGradient: LinearGradient {
        LinearGradient(colors: [
            Color(red: 0x4E / 255, green: 0x7B / 255, blue: 0xF5 / 255),
            Color(red: 0x7B / 255, green: 0x5C / 255, blue: 0xF0 / 255)
        ], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// 页面背景：浅色 #F4F5FB，深色用系统背景
    static let background = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor.systemBackground
            : UIColor(red: 0xF4 / 255, green: 0xF5 / 255, blue: 0xFB / 255, alpha: 1)
    })

    /// 助手气泡：浅色白底，深色二级背景
    static let assistantBubble = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor.secondarySystemBackground
            : .white
    })

    /// 代码块：日夜间均为深色底 #23253A / 浅字 #E6E9F5
    static let codeBackground = Color(red: 0x23 / 255, green: 0x25 / 255, blue: 0x3A / 255)
    static let codeText = Color(red: 0xE6 / 255, green: 0xE9 / 255, blue: 0xF5 / 255)

    /// 状态条
    static let statusBackground = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(red: 0x4A / 255, green: 0x3F / 255, blue: 0x14 / 255, alpha: 1)
            : UIColor(red: 1, green: 0xF6 / 255, blue: 0xDE / 255, alpha: 1)
    })
    static let statusText = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(red: 1, green: 0xDD / 255, blue: 0x88 / 255, alpha: 1)
            : UIColor(red: 0x8D / 255, green: 0x6E / 255, blue: 0, alpha: 1)
    })

    static let error = Color(red: 0xD3 / 255, green: 0x2F / 255, blue: 0x2F / 255)
}

# Kimi Mobile (iOS)

Kimi Code web 服务端的 iOS 客户端（SwiftUI，iOS 16+）。功能与 Android/macOS 版对齐。

## 构建与部署

- Xcode 16.4（本机装在外接盘镜像 `/Volumes/Xcode`，**构建/部署时需保持挂载**）
- 工程文件由 XcodeGen 生成：`xcodegen generate`（若 `.xcodeproj` 缺失）
- 模拟器编译：`xcodebuild -project KimiMobile.xcodeproj -scheme KimiMobile -destination 'generic/platform=iOS Simulator' build`
- 真机部署（免费 Apple ID，Personal Team 签名，7 天过期）：

```bash
./redeploy.sh   # archive + 导出 + 安装到已连接设备
```

首次在 iPhone 打开需在 设置→通用→VPN与设备管理 信任开发者。7 天到期后重跑 redeploy.sh 续期。

## 协议要点

同 Android 版 README（docs 见 Android 仓库）。WS 认证在握手头；prompts 顶层带 model + 模式字段；幻影 user 消息不渲染。

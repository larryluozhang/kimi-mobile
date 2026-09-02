#!/bin/bash
#
# redeploy.sh — 一条命令完成 archive + 导出 + 安装到已连接的 iPhone/iPad
#
# 前置条件：
#   1. Xcode 里登录过 Apple ID（免费个人开发者账号即可）：
#      Xcode → Settings → Accounts → + 添加 Apple ID
#   2. 设备已用数据线连接本机并信任；手机上 设置→通用→VPN与设备管理 信任开发者证书
#   3. （首次）在 Xcode 里打开工程，Signing & Capabilities 选你的 Personal Team；
#      或直接把 TEAM_ID 环境变量传进来
#
# 用法：
#   ./redeploy.sh                     # 自动选第一台已连接设备
#   TEAM_ID=ABCDE12345 ./redeploy.sh  # 指定开发团队
#   DEVICE="我的 iPhone" ./redeploy.sh # 指定设备名
#
set -euo pipefail

cd "$(dirname "$0")"

PROJECT=KimiMobile.xcodeproj
SCHEME=KimiMobile
BUILD_DIR=build
ARCHIVE=$BUILD_DIR/KimiMobile.xcarchive
EXPORT_DIR=$BUILD_DIR/export
EXPORT_PLIST=$BUILD_DIR/ExportOptions.plist

# 工程目录是 git 管理的也可以直接用；这里每次重新生成以确保 project.yml 生效
if command -v xcodegen >/dev/null 2>&1; then
    echo "==> 重新生成 Xcode 工程（xcodegen）"
    xcodegen generate
fi

# ---- 1. Archive（真机目标，自动签名）---------------------------------------
echo "==> Archive（需要已登录 Apple ID，免费账号即可）"
xcodebuild -project "$PROJECT" -scheme "$SCHEME" \
    -destination 'generic/platform=iOS' \
    -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates \
    ${TEAM_ID:+DEVELOPMENT_TEAM="$TEAM_ID"} \
    archive

# ---- 2. 导出 .app（development 方式，可装到本账号注册的设备）----------------
echo "==> 导出 development 包"
mkdir -p "$BUILD_DIR"
cat > "$EXPORT_PLIST" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>development</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>compileBitcode</key>
    <false/>
</dict>
</plist>
EOF
xcodebuild -exportArchive \
    -archivePath "$ARCHIVE" \
    -exportPath "$EXPORT_DIR" \
    -exportOptionsPlist "$EXPORT_PLIST" \
    -allowProvisioningUpdates

# 导出产物是 .ipa（文件名可能含空格，如 "Kimi Mobile.ipa"），动态查找
APP=$(ls "$EXPORT_DIR"/*.ipa 2>/dev/null | head -1)
[ -n "$APP" ] && [ -f "$APP" ] || { echo "导出失败：$EXPORT_DIR 下找不到 .ipa"; exit 1; }

# ---- 3. 安装到已连接设备 ---------------------------------------------------
# 优先 devicectl（Xcode 15+ 自带）；没有的话回退 ios-deploy（brew install ios-deploy）
# 注意：macOS 自带 bash 3.2 下空数组 "${DEVICE_ARG[@]}" 会报 unbound variable，用拼字符串规避
DEVICE_ARG=""
if [ -n "${DEVICE:-}" ]; then
    DEVICE_ARG="--device $DEVICE"
fi

if xcrun devicectl --help >/dev/null 2>&1; then
    echo "==> 安装到设备（devicectl）"
    # shellcheck disable=SC2086
    xcrun devicectl device install app $DEVICE_ARG "$APP"
elif command -v ios-deploy >/dev/null 2>&1; then
    echo "==> 安装到设备（ios-deploy）"
    ios-deploy --bundle "$APP" ${DEVICE:+--id "$DEVICE"}
else
    echo "未找到 devicectl / ios-deploy，请手动安装：$APP"
    exit 1
fi

echo "==> 完成！手机上首次打开需在 设置→通用→VPN与设备管理 中信任开发者。"

#!/bin/bash
# release_latest.sh — 发布应用到 app-latest 更新通道（GitHub Releases）
# 用法: release_latest.sh <apk路径> <dmg路径> <windows-zip路径> <android_version_name> <android_version_code> <macos_semver> <ios_version> <ios_build> [notes文件]
# 例:  tools/release_latest.sh ~/Downloads/kimi-mobile.apk "./Kimi Mobile.dmg" ~/Downloads/Kimi-Mobile-windows.zip 0.7.4 26 0.7.4 0.7.4 11 /tmp/notes.txt
# 注意：macos_semver 必须与 build.gradle.kts 的 project version（AppVersion.CURRENT）同轴（如 0.7.4），不要填 packageVersion
set -euo pipefail

APK="${1:?apk path}"; DMG="${2:?dmg path}"; WINZIP="${3:?windows zip path}"
AVN="${4:?android versionName}"; AVC="${5:?android versionCode}"
MVER="${6:?macos 语义版本（与 AppVersion.CURRENT 同轴，如 0.7.4）}"; IVN="${7:?ios versionName}"; IBD="${8:?ios build}"
NOTES_FILE="${9:-}"
REPO="larryluozhang/kimi-mobile"
BASE="https://github.com/${REPO}/releases/download/app-latest"

[ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }
[ -f "$DMG" ] || { echo "DMG 不存在: $DMG"; exit 1; }
[ -f "$WINZIP" ] || { echo "Windows zip 不存在: $WINZIP"; exit 1; }
NOTES=""; [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ] && NOTES=$(cat "$NOTES_FILE")

sha() { shasum -a 256 "$1" | awk '{print $1}'; }
esc() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cp "$APK" "$TMP/kimi-mobile.apk"
cp "$DMG" "$TMP/Kimi-Mobile.dmg"
cp "$WINZIP" "$TMP/Kimi-Mobile-windows.zip"

python3 - "$TMP/latest.json" <<EOF
import json
manifest = {
  "android": {"version_name": "$AVN", "version_code": $AVC,
              "url": "$BASE/kimi-mobile.apk", "sha256": "$(sha "$APK")", "notes": $(printf '%s' "$NOTES" | esc)},
  "macos":   {"version": "$MVER",
              "url": "$BASE/Kimi-Mobile.dmg", "sha256": "$(sha "$DMG")", "notes": $(printf '%s' "$NOTES" | esc)},
  "windows": {"version": "$MVER",
              "url": "$BASE/Kimi-Mobile-windows.zip", "sha256": "$(sha "$WINZIP")", "notes": $(printf '%s' "$NOTES" | esc)},
  "ios":     {"version": "$IVN", "build": $IBD, "notes": $(printf '%s' "$NOTES" | esc)},
}
import sys
json.dump(manifest, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
EOF

HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}" gh release upload app-latest \
  "$TMP/kimi-mobile.apk" "$TMP/Kimi-Mobile.dmg" "$TMP/Kimi-Mobile-windows.zip" "$TMP/latest.json" \
  --repo "$REPO" --clobber
echo "OK: app-latest 已更新 -> android $AVN($AVC) / desktop $MVER (macos+windows) / ios $IVN($IBD)"

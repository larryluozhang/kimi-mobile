# Kimi Mobile

Unofficial multi-platform native clients for the [Kimi Code](https://github.com/MoonshotAI/kimi-code) local server (`kimi web`): Android (APK) / iOS / macOS (DMG) / Windows (portable zip, built via Compose Multiplatform `createDistributable`).

- Multi-host profiles (address + token), session/workspace switching, WS streaming chat, tool activity feed, approval/question cards, interrupt button, queued/executing/undelivered states, session mode bar, version display
- In-app update channel (GitHub Releases `app-latest`), permission modes with plain naming and danger confirmation, plan-review approvals showing the full plan text, per-session input drafts, partial text copy, slash-command completion
- Voice input: Android downloads the offline speech engine on demand (5 MB APK, model fetched in-app); iOS uses system speech recognition only

## Repository Layout

- `android/` — Android client (Kotlin)
- `macos/` — Compose Desktop client (Kotlin)
- `ios/` — iOS client (SwiftUI + XcodeGen)
- `server/` — one-command server-side installer (`install.sh` for macOS/Linux, `install.ps1` for Windows)
- `docs/` — Server API docs + field-tested protocol notes (`docs/PROTOCOL-NOTES.md` is a must-read)

## Server setup

One command turns a machine into a server the app can connect to (Kimi Code daemon with bearer token, autostart, firewall rule, self-check):

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/larryluozhang/kimi-mobile/main/server/install.sh | bash
```

```powershell
# Windows
irm https://raw.githubusercontent.com/larryluozhang/kimi-mobile/main/server/install.ps1 | iex
```

The installer is idempotent and handles two cases:

- **Existing kimi-code (Group A):** detected installs are left untouched — no reinstall, `config.toml` never modified; only the daemon/autostart layer is added. A model provider is offered only if none is configured.
- **Fresh machine (Group B):** runs the official kimi-code installer, then offers OAuth sign-in (recommended) or API-key model setup.

On success it prints a **connect card** — a deeplink (`kimi-mobile://connect?...`) plus the equivalent JSON. In the app, choose **Settings → Import server** and paste either one. Full details: `server/README.md`.

## Build

See each subdirectory's README for build instructions. One prerequisite is not in git: the Android build needs `sherpa-onnx-1.13.7.aar` from the [sherpa-onnx GitHub Releases](https://github.com/k2-fsa/sherpa-onnx/releases) placed in `android/app/libs/` (gitignored for size).

Protocol behavior documented here is based on real-world testing (baseline: server 0.35.0–0.37.2).

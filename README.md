# Kimi Mobile

Unofficial multi-platform native clients for the [Kimi Code](https://github.com/MoonshotAI/kimi-code) local server (`kimi web`): Android / macOS / iOS.

- Multi-host profiles (address + token), session/workspace switching, WS streaming chat, tool activity feed, approval/question cards, interrupt button, queued/executing/undelivered states, session mode bar, version display

## Repository Layout

- `android/` — Android client (Kotlin)
- `macos/` — Compose Desktop client (Kotlin)
- `ios/` — iOS client (SwiftUI + XcodeGen)
- `docs/` — Server API docs + field-tested protocol notes (`docs/PROTOCOL-NOTES.md` is a must-read)

See each subdirectory's README for build instructions. Protocol behavior documented here is based on real-world testing (baseline: server 0.35.0–0.37.2).

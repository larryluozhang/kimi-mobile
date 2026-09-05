# Kimi Mobile (macOS)

macOS desktop client for the Kimi Code web server (Compose Multiplatform Desktop). Feature parity with the Android client: gate screen, session/workspace switching, WS streaming chat, multi-host profiles, session mode bar, approval/question cards, fork/rename, history pagination, slash commands, version display.

## Build

Requires JDK 17+:

```bash
./gradlew clean packageDmg
```

**Note:** the Compose plugin's `createDistributable` incremental cache can silently package a stale jar — always release with `clean` and verify the jar version inside the DMG.

## Note: hand-written network layer

`MiniHttp.kt` (NIO HTTP) and `WsClient.kt` (NIO WebSocket framing) are hand-written — on some older machines (e.g. OCLP-patched Macs) the JVM's high-level network stack (`java.net.Socket` / `HttpClient` / OkHttp) can connect but never reads data, while NIO `SocketChannel` works. If your JVM networking is healthy you could swap back to OkHttp, but it is not recommended.

## Protocol notes

See `docs/PROTOCOL-NOTES.md` at the repository root. Debug log: `~/.kimi-mobile/app.log`; `--e2e` / `--e2e-profile` run headless self-checks.

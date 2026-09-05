# Kimi Mobile (macOS)

macOS desktop client for the Kimi Code web server (Compose Multiplatform Desktop). Feature-parity with the Android version: Tailscale gating, session/workspace switching, WS streaming chat, multi-host profiles, session mode bar.

## Build

Requires a JDK (a portable one lives at `~/kimi-desktop-toolchain/jdk-21.0.12+8`):

```bash
JAVA_HOME=~/kimi-desktop-toolchain/jdk-21.0.12+8/Contents/Home ./gradlew clean packageDmg
```

**Note**: the Compose plugin's createDistributable incremental cache can silently package a stale jar; always run `clean` before a release and check the jar version inside the DMG.

## Important: this machine's network stack

On this machine (OCLP macOS 15) the JVM high-level network stack is broken: java.net.Socket/HttpClient/OkHttp all "connect but read no data"; only NIO SocketChannel works. The network layer is therefore hand-written: `MiniHttp.kt` (NIO HTTP) and `WsClient.kt` (NIO WebSocket frame codec) — **do not switch back to OkHttp**.

## Protocol Highlights

Same as the Android README. Debug log at `~/.kimi-mobile/app.log`; `--e2e` / `--e2e-profile` provide windowless self-checks.

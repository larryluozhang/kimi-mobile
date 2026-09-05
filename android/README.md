# Kimi Mobile (Android)

Native Android client for the Kimi Code web server. Connects to a self-hosted kimi web instance (REST + WebSocket); supports streaming chat, voice input, workspace switching, multi-host profiles, and the session mode bar (plan / Swarm / permission / model / goal).

## Build

The build machine is host 146 (Linux): SDK at `/opt/kimi/android-sdk`, Gradle 8.9.

```bash
ANDROID_HOME=/opt/kimi/android-sdk ./gradlew assembleRelease
```

Signing requires `keystore.properties` in the project root (not committed):

```properties
storeFile=/opt/kimi/android-sdk/keystore/kimi-mobile.jks
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

## Protocol Highlights

- Server docs: `docs/kimi-openapi.json` / `docs/kimi-asyncapi.json`
- WS auth rides in the handshake HTTP header; reply to the 10s JSON ping with a pong carrying the same nonce; streaming arrives as transcript.ops frames
- POST prompts must include `model` at the top level; mode fields ride along with prompts
- System-injected user messages (<system-reminder> etc.) are not rendered

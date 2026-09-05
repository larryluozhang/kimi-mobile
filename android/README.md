# Kimi Mobile — Android

Native Android client for the Kimi Code local server (`kimi web`).

## Build

Requires Android SDK (API 34) and JDK 17+:

```bash
./gradlew assembleRelease
```

To sign a release build, create `keystore.properties` in the project root (**never commit it**):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

Without it, use `./gradlew assembleDebug` for a debug build.

## Offline voice model

Voice input uses a sherpa-onnx streaming bilingual (zh/en) model, downloaded on demand inside the app (Settings → Download offline model). Nothing to do at build time; the engine falls back to the system recognizer when the model is not installed.

## Structure

- `Api.kt` — REST client (sessions/workspaces/messages/profile/approvals/questions/queue/abort/fork/undo)
- `WsClient.kt` — WebSocket client (heartbeat, subscriptions, transcript.ops parsing)
- `ChatActivity.kt` — chat screen: streaming rendering, tool activity, approval/question dialogs, queued/executing/undelivered reconciliation, history pagination, slash commands, voice input
- `SessionsActivity.kt` — session list (workspace switching, auto refresh, busy badges)
- `Prefs.kt` — host profiles and local persistence (SharedPreferences)

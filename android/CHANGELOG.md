# Changelog

## 0.7.3 (2026-09-10)
- Image+text messages (sending images): the input bar gains an image button using the system Photo Picker (ACTION_PICK_IMAGES, no storage permission needed); the picked image is uploaded on a background thread via POST /api/v1/files (multipart, field name file, Api.uploadFile returns data.id), while a thumbnail preview bar is shown above the input area ("Uploading…"/"Pending" + remove button; upload failure shows a Toast and clears it); on send, the image block ({"type":"image","source":{"kind":"file","file_id":...}}) is interleaved with the text block and dispatched with the prompt (Api.sendPrompt gains an optional imageFileId parameter); image-only messages (no text) can also be sent; tapping send while an image is still uploading asks you to wait
- History image display: Api.getMessages parses image blocks and extracts source.file_id (HistoryMessage gains imageFileId; text filtering logic unchanged; pure-image messages are kept); bubbles embed an ImageView, and when imageFileId is non-empty a background thread GET /api/v1/files/{id} fetches the binary for decoding and display (downsampled to ~1280px on the long edge; MessageAdapter has a built-in simple file_id→Bitmap in-memory cache, using tag to guard against recycled-view misplacement); both user/assistant bubbles are supported; local optimistic echoes with imageFileId display immediately, and echo confirmation matching additionally compares the image
- Pure-image messages hide the empty text bubble, and the long-press entry is attached to the image (the Copy/Fork-from-here menu is unchanged)
- Model selection switched to a dynamic server-side list: Api gains listModels (GET /api/v1/models → data.items[], containing provider/model/display_name/max_context_size) plus a shared fallback preset MODEL_PRESETS; the chat page mode-bar model Spinner fetches the server list on session entry (displays display_name, value is the full model id), and silently falls back to the 4 presets (k3 / kimi-for-coding / kimi-for-coding-highspeed / k3-256k) on failure/empty; newly configured server-side models (e.g. amd/deepseek-v4-flash) become visible directly; the current session model is appended and kept if not in the list
- The settings page global model selection changed from a manual EditText to the same dynamic Spinner (fetched on entering the settings page, falls back to presets on failure/empty; a saved custom value not in the list is appended and kept); saving uses the full model id of the selected item
- Fixed the context limit not matching the current model (selecting k3-256k still showed 1000k): limit priority is now current model's max_context_size > WS maxContextTokens > fallback 1048576; the current model comes from the session-level mode-slot model (falls back to global Prefs.model if empty); the limit refreshes after a model switch or when the model list arrives

## 0.7.2 (2026-09-05)
- History message pagination, fixing heavy-tool sessions "showing only 3 messages": GET /sessions/{id}/messages returns only the latest 100 **raw** messages (including the tool role), so after filtering only a few visible bubbles may remain; Api.getMessages gains an optional beforeId parameter (before_id verified effective), returning MessagesPage (visible messages + oldest raw message id anchor + raw count; hasMore roughly judged by whether the raw count reaches page_size=100)
- Added a "Load earlier messages" entry at the top of the chat page (shown only when more may exist; shows "Loading…" while loading): on tap it fetches the previous page using the current oldest raw message id as before_id and prepends the results into the list (earlier timestamps, so after the existing timeMillis-sorted reconciliation they naturally land at the front; deduplicated against the latest page by id; queued/running/undelivered bubbles unaffected); prepend refresh keeps the reading position instead of jumping to the bottom; loading 0 items shows "No earlier messages" and hides the entry; when an entire page is filtered out (all tool) it hints that loading can continue

## 0.7.1 (2026-09-05)
- Fixed the context usage display: limit takes WS maxContextTokens (adopted only when >0), otherwise falls back to 1048576 (1M, the observed server snapshot value); display format fixed as "usage/limit (percent)" (e.g. "Context 690/1000k (0%)"), percent is always shown; fmtTokens decimal formatting pinned to US locale, fixing display errors like "690.k"
- Added "Fork from here": long-press a user bubble to open a menu (Copy / Fork from here); fork logic: count the user messages n after this message that are already in server-side history → :fork full clone → :undo {"count":n} on the new session to trim the content after it → jump to the new session (Api gains undoSession, POST /sessions/{id}:undo); the status bar shows "Forking…" during the process, fork failure shows a Toast, undo failure keeps the new session and warns the content was not trimmed

## 0.7.0 (2026-09-05)
- Context usage display: added "Context 23.5k/1000k (2%)" to the right of the mode summary bar; data source priority: 1) WS transcript.reset snapshot meta.agent.contextTokens/maxContextTokens 2) transcript.ops meta.merge agent.contextTokens 3) GET /sessions/{id} usage.context_tokens/context_limit (observed to possibly be all 0 — adopted only when non-zero, fallback only); refreshed on busy polling (loadHistory) and WS events; hidden when there is no data
- Added a "Fork" button in the chat page header: same code path as the /fork command (Api.sessionAction("fork") → jump to the new session)
- Added /rename (/title) command: takes all text after the first space as the new title, POST /sessions/{id}/profile with a top-level title field (not metadata.title); empty input shows a Toast with usage; success shows a Toast and updates the top title; /help command list updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / command matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (APK size dropped from ~213MB back to ~35MB): removed the built-in assets/models/ model; SpeechOnnx now loads from filesDir/models/zipformer-bilingual/ (usable only when all four files are present; OnlineRecognizer is passed a null assetManager to load by absolute path); the settings page voice card gains a "Download offline model" button + editable download URL input (stored in Prefs voice_model_url, default GitHub Releases v0.6.1-models); HttpURLConnection downloads the zip on a background thread to filesDir/tmp then unzips it (java.util.zip, matching the 4-file set by filename, tolerating one nested directory level in the zip); progress (downloaded MB/total MB) is shown next to the button; ChatActivity integration: in onnx-forced mode, tapping the mic without the model downloaded prompts "Please download the offline model from the settings page first"; in auto mode a missing model silently falls back to system recognition
- Slash command support in the input field: intercepts text starting with / before sending; exact matches (case-insensitive) of /compact /archive /fork /abort /stop /new /help go to the server session action POST /api/v1/sessions/{id}:{action} (Api.sessionAction) or a local flow; /archive returns to the session list on success, /fork jumps to the new session on success, /new reuses the list-page session creation flow (workspace selection strategy consistent with SessionsActivity), /help shows a dialog listing the commands; other text starting with / is sent as a regular prompt (consistent with the official behavior); all commands run on a background thread with results reported via Toast/status bar

## 0.6.0 (2026-09-05)
- Added bundled offline speech recognition engine sherpa-onnx (1.13.7 local AAR, Apache-2.0): built-in bilingual Chinese-English streaming model zipformer-bilingual-2023-02-20 (int8 quantized, ~190MB in assets, not committed to git); AudioRecord 16kHz mono capture + streaming recognition, partial results shown in the status bar, final result appended to the input field on endpoint/stop; tapping the mic again during recognition ends it and flushes the trailing result
- Settings page gains a "Speech engine" option (auto/onnx/system, default auto): in auto mode the offline engine is preferred when the model exists, and missing model or initialization failure automatically falls back to the system SpeechRecognizer (old path kept); onnx forces offline, system forces system
- Recording permission reuses the existing RECORD_AUDIO request logic

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area height-limited and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages on the sender side: the system-injection filter adds the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages on the sender side: the system-injection filter adds the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages on the sender side: the system-injection filter adds the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added Q&A support (pending_interaction="question" scenario): when the server sends AskUserQuestion, ChatActivity polls GET /questions?status=pending every 5s (sharing the 5s polling timer with approvals) and shows a dialog when there are pending items: each question renders a RadioGroup single choice (label — description); when allow_other, an "Other (custom answer)" option + text input is appended; submitting sends {"kind":"single","option_id":...} / {"kind":"other","text":...}; the "Skip" button sends {"kind":"skipped"}; the dialog does not close until all questions are answered (submit button validation taken over manually); multiple pending items are handled one by one (same as approvals)

## 0.4.9 (2026-08-21)
- Settings page bottom shows the current version number (versionName + versionCode, from BuildConfig)

## 0.4.8 (2026-08-20)
- Fixed conversation order corruption: loadHistory reconciliation previously appended local echo/queued/running bubbles unconditionally at the end of the list, so order broke when they were earlier than the latest history entry; now the whole list is sorted ascending by timeMillis before setAll (history uses created_at, queued/active bubbles use the queue API's created_at, local echoes use send time, entries without a timestamp go last)
- Api.listQueuedPrompts now parses created_at as well: new QueuedPrompt(text, createdAt); PromptQueue.queued/active both changed to QueuedPrompt
- Fallback reconciliation even when idle: history polling is now always-on in the foreground (busy 15s / idle 60s, stopped in onPause, no stacking); after WS stabilized, badge states (queued/running/undelivered) of idle sessions no longer freeze
- Added reconciliation diagnostic logs (tag "Reconcile"): history count, queue/active summary, and the verdict for each local echo (history-confirmed/server queue/running/undelivered/POST in flight)

## 0.4.7 (2026-08-20)
- Fixed "entering a session mid-turn shows no progress at all" (v0.37.2 server: a WS client that subscribes mid-turn receives none of that turn's transcript.ops, including streaming content and the turn.upsert completion event):
  - The transcript.reset branch parses payload.snapshot.meta.agent.phase for the real-time phase and calls back onPhase (among multiple resets from multiple agents, takes main: agent_id=="main" or one with meta), so entering a session immediately shows "Working/Thinking" (new tool_call phase display)
  - Polls loadHistory every 15s while busy (started when turnActive, stopped in onPause, no stacked timers); when polling detects busy has disappeared (data.active empty and queue drained) → an immediate unified refresh; onWorkChanged(false)/phase ended also trigger a fallback refresh. A reply is visible within 15s of the in-flight turn completing, no longer depending on the unreceived turn.upsert
- Running is no longer labeled "queued": GET /prompts?status=queued's data.active is the currently executing prompt (v0.37.2, not in queued[]); matching a local echo → labeled "Running" in small text (ChatMsg gains an active field); when re-entering a session with no echo, an active-0 server bubble is rendered; messages in active are not labeled "undelivered"
- Api.listQueuedPrompts return type changed to PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- Fixed stale "Running" badge in the session list: loadAll() now runs automatically every 30s while resumed (stopped in onPause; loadAll is read-only and does not interrupt pull-to-refresh/typing)
- Fallback for queued messages silently dropped by the server (upstream bug #3127: queued prompts under phantom busy are silently discarded): a local echo that is neither in history nor in the server queue and is older than 60s → marked "Undelivered (dropped by server)" (red warning, no longer shows "Queued"); POST in flight (<60s) is kept normally
- ChatMsg gains an undelivered field; queued-* bubble behavior unchanged (rebuilt from the server queue each round, disappears naturally when absent from both queue and history)

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session/killing the app": the server queue is now the source of truth; loadHistory also fetches GET /prompts?status=queued, and messages in the queue are rendered as user bubbles with a "Queued" small-text marker (deduplicated against local echoes with the same text, shown only once)
- pendingLocal reconciliation upgrade: confirmed in history → removed; present in the server queue → rendered by the server-queue bubble instead; in neither history nor queue (POST in flight) → kept
- When the queue API fetch fails, falls back to the old behavior (keep all unconfirmed echoes) without blocking history loading
- When POST returns queued, the local echo is immediately marked "Queued" (MessageAdapter gains markQueued; ChatMsg gains a queued field)

## 0.4.3 (2026-08-20)
- Fixed "sent messages disappear": when busy, the server returns queued and the message does not enter history yet, so loadHistory's setAll wiped the optimistic echo → now maintains pendingLocal, appending unconfirmed echoes after the history; they survive reconnects/refreshes and are auto-confirmed and removed once a user message with the same text appears in history
- sendPrompt returns the server status: when queued, the status bar shows "Queued, waiting for the current task to finish…"
- The optimistic echo is retracted on send failure (MessageAdapter gains removeById)
- Added tool activity stream: parses WS frame.upsert kind=tool frames (display.summary ?: inputText ?: input, truncated to 80 chars), inserting small gray temporary entries in the message list (running 🔧 / done ✓); the status bar shows "Working: tool name"; cleared naturally with loadHistory when the turn ends

## 0.4.2 (2026-08-15)
- Fixed "sessions lost": listSessions no longer passes busy=false, so running/pending-approval sessions are visible again, with a "Running" marker next to the title
- Added tool approvals: after entering a session, polls pending approvals every 5 seconds (foreground only), showing a dialog with the tool name + summary, supporting approve/reject; multiple items handled one by one
- Default workspace changed to "last selected → workspace with the largest session_count → the first one" (the old logic hardcoded a Linux path and wrongly landed on Downloads on Mac/iOS hosts)
- WorkspaceItem gains session_count parsing

## 0.4.1 (2026-08-15)
- Mode state mechanism aligned with the official web UI: persisted locally per session (SharedPreferences); when sending, prompts carry mode fields at the top level (plan_mode/swarm_mode/permission_mode/model)
- Background: server v0.35.0 GET /profile does not return the real agent_config, so its echo cannot be trusted

## 0.4 (2026-08-15)
- Session mode bar: plan mode / Swarm toggle, permission mode (Manual/Auto/YOLO), model switching, goal mode (set/pause/resume/cancel)
- Fix: the cleartext HTTP whitelist hardcoded the 146 address so no other host could be added → allow cleartext globally (private tailnet, internal use)
- Fix: the gate page gains a permanent "Server settings" entry to avoid being locked out when the connection fails
- Fix: Tailscale launch (Android 11+ package visibility queries declaration) + system VPN settings fallback

## 0.3.2 (2026-08-15)
- Fixed user bubble text being horizontally truncated (layout constraints)
- Fixed the last message being covered by the input field (RecyclerView padding + scroll timing)
- Fixed history messages in reverse order (the API returns newest first; must be reversed)

## 0.3 (2026-08-15)
- Filter system-injected phantom user messages (blocks starting with <system-reminder> / <cron-fire)

## 0.2 (2026-08-14)
- Material Design 3 polish: new icon (blue-purple gradient + bubble K), day/night dual themes, splash, redesigned chat bubbles, long-press to copy, code block rendering

## 0.1 (2026-08-14)
- First release: Tailscale gate, session list + workspace switching, WS streaming chat (transcript.ops), voice input (SpeechRecognizer zh-CN), multi-host profiles

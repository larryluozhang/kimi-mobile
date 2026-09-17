# Changelog

## 0.7.3 (2026-09-10)
- Image messages (send pictures): new image button in the input bar, using the system Photo Picker (ACTION_PICK_IMAGES, no storage permission required); after selection, uploads on a background thread via POST /api/v1/files (multipart, field name file, Api.uploadFile returns data.id), while a thumbnail preview strip is shown above the input area (uploading.../pending + remove button, Toast on upload failure and cleared); when sending, the image block ({"type":"image","source":{"kind":"file","file_id":...}}) is interleaved with text blocks and sent together with the prompt (Api.sendPrompt gains an optional imageFileId parameter); image-only messages (no text) can also be sent; tapping send while an image is still uploading prompts to wait
- History image display: Api.getMessages parses image blocks and extracts source.file_id (HistoryMessage gains imageFileId, text filtering logic unchanged, image-only messages retained); an ImageView is embedded in the bubble, and when imageFileId is non-empty the binary is fetched on a background thread via GET /api/v1/files/{id} and decoded for display (downsampled to ~1280px on the long edge, MessageAdapter has a built-in simple in-memory file_id-to-Bitmap cache, tag prevents recycled-view misplacement); supported in both user and assistant bubbles; local optimistic echo with imageFileId displays immediately, and echo confirmation matching now also compares images
- Image-only messages hide the empty text bubble, with the long-press entry attached to the image (Copy / Fork from here menu unchanged)
- Model selection switched to a server-driven dynamic list: Api gains listModels (GET /api/v1/models -> data.items[], containing provider/model/display_name/max_context_size) and shared fallback presets MODEL_PRESETS; the model Spinner in the chat page mode bar fetches the server list when entering a session (displays display_name, value is the full model id), and silently falls back to 4 presets on failure/empty (k3 / kimi-for-coding / kimi-for-coding-highspeed / k3-256k), so newly configured server-side models (e.g. amd/deepseek-v4-flash) are visible directly; if the current session model is not in the list it is appended and retained
- Global model selection on the Settings page changed from a free-text EditText to the same dynamic Spinner (fetches when entering Settings, falls back to presets on failure/empty; a saved custom value not in the list is appended and retained); saving uses the full model id of the selected item
- Fixed context limit not matching the current model (selecting k3-256k still showed 1000k): limit priority is now current model's max_context_size > WS maxContextTokens > fallback 1048576; the current model is taken from the session-level mode-bar model (falling back to global Prefs.model if empty), and the limit refreshes after model switch/model list arrival
- Fixed auto-refresh forcing the reading position to the bottom: before each loadHistory polling refresh, checks whether the user is near the bottom (2-item tolerance); if at the bottom, scrolls to the bottom as before; otherwise records the first visible item's message id + offset, and after setAll finds the new index by id to restore the reading position (prepend pagination no longer drifts); streaming frame follow (refreshStreamingBubble) likewise only follows when at the bottom
- Hardened keyboard occlusion of the input box: ChatActivity adds an IME insets listener (ViewCompat.setOnApplyWindowInsetsListener); when the IME is visible the chat scrolls to the bottom so the last message + input box are not covered; for third-party IMEs (e.g. MIUI) with abnormal insets, when the IME bottom edge covers the input bar, paddingBottom is manually added to the input bar (only when the difference > 0), and reset when the IME closes

## 0.7.2 (2026-09-05)
- Paginated history message loading, fixing tool-heavy sessions showing "only 3 messages": GET /sessions/{id}/messages returns only the latest 100 raw messages (including the tool role), so after filtering only a few visible bubbles may remain; Api.getMessages gains an optional beforeId parameter (before_id verified to work), returning MessagesPage (visible messages + oldest raw message id anchor + raw count, hasMore roughly determined by whether the raw count reaches page_size=100)
- New "Load earlier messages" entry at the top of the chat page (shown only when more messages may exist, shows "Loading..." while loading): on tap, fetches the previous page using the current oldest raw message id as before_id, and prepends the results into the list (earlier timestamps, naturally sorted first after the existing timeMillis-based reconciliation, deduplicated against the latest page by id; queued/active/undelivered bubbles unaffected); prepend refresh keeps the reading position without jumping to the bottom; loading 0 items shows "No earlier messages" and hides the entry, and when an entire page is filtered out (all tool) it prompts that loading can continue

## 0.7.1 (2026-09-05)
- Fixed context usage display: limit takes WS maxContextTokens (only adopted when >0), otherwise falls back to 1048576 (1M, verified server snapshot value); display format fixed as "usage/limit (percentage)" (e.g. "Context 690/1000k (0%)"), always including the percentage; fmtTokens decimal formatting pinned to US locale, fixing display errors like "690.k"
- New "Fork from here": long-press a user bubble to show a menu (Copy / Fork from here); fork logic: count the number n of user messages after this message that have entered server history -> :fork full clone -> :undo {"count":n} on the new session to trim the subsequent content -> jump to the new session (Api gains undoSession, POST /sessions/{id}:undo); the status bar shows "Forking..." during the process, Toast on fork failure, and on undo failure the new session is kept with a notice that content was not trimmed

## 0.7.0 (2026-09-05)
- Context usage display: new "Context 23.5k/1000k (2%)" on the right side of the mode summary bar; data source priority: 1. WS transcript.reset snapshot meta.agent.contextTokens/maxContextTokens 2. transcript.ops meta.merge agent.contextTokens 3. GET /sessions/{id} usage.context_tokens/context_limit (may be all 0 in practice, only adopted when non-zero, fallback only); refreshed during busy polling (loadHistory) and on WS events, hidden when no data
- New "Fork" button in the chat page header: same code path as the /fork command (Api.sessionAction("fork") -> jump to the new session)
- New /rename (/title) command: takes all text after the first space as the new title, POST /sessions/{id}/profile top-level title field (not metadata.title); empty input shows a usage Toast, success shows a Toast + syncs the header title; the /help command list updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / commands matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (APK size back down from ~213MB to ~35MB): removed the bundled assets/models/ model; SpeechOnnx now loads from filesDir/models/zipformer-bilingual/ (usable only when all four files are present, OnlineRecognizer passed a null assetManager to load by absolute path); the Settings page speech card gains a "Download offline model" button + editable download URL input (stored in Prefs voice_model_url, defaulting to GitHub Releases v0.6.1-models), HttpURLConnection downloads the zip on a background thread to filesDir/tmp then extracts it (java.util.zip, matching the 4-file set by filename, tolerant of one directory level inside the zip), with progress (downloaded MB/total MB) shown next to the button; ChatActivity integration: in onnx-forced mode, tapping the microphone when the model is not downloaded prompts "Please download the offline model in Settings first"; in auto mode a missing model silently falls back to system recognition
- Slash command support in the input box: intercepts text starting with / before sending, exact match (case-insensitive) of /compact /archive /fork /abort /stop /new /help routed to the server session action POST /api/v1/sessions/{id}:{action} (Api.sessionAction) or a local flow; /archive returns to the session list on success, /fork jumps to the new session on success, /new reuses the list-page session creation flow (workspace selection strategy consistent with SessionsActivity), /help shows a dialog listing the commands; other text starting with / is sent as a normal prompt (consistent with the official behavior); all commands run on a background thread with Toast/status bar feedback

## 0.6.0 (2026-09-05)
- New bundled offline speech recognition engine sherpa-onnx (1.13.7 local AAR, Apache-2.0): built-in Chinese-English bilingual streaming model zipformer-bilingual-2023-02-20 (int8 quantized, ~190MB assets, not committed to git); AudioRecord 16kHz mono capture + streaming recognition, partials shown in the status bar, final result appended to the input box on endpoint/stop; tapping the microphone again during recognition stops it and flushes the trailing result
- Settings page gains a "Speech engine" option (auto/onnx/system, default auto): under auto, offline is preferred when the model exists, and missing model or initialization failure automatically falls back to the system SpeechRecognizer (old path retained); onnx forces offline, system forces system
- Recording permission reuses the existing RECORD_AUDIO request logic

## 0.5.2 (2026-09-02)
- Question card/dialog content area height-limited and scrollable: the submit button at the bottom is always reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- New question support (pending_interaction="question" scenario): when the server sends AskUserQuestion, ChatActivity polls GET /questions?status=pending every 5s (sharing the 5s polling timer with approvals), and shows a dialog when pending exists: each question renders a RadioGroup single choice (label - description), appending "Other (custom answer)" + text input when allow_other; submits {"kind":"single","option_id":...} / {"kind":"other","text":...}; the "Skip" button sends {"kind":"skipped"}; the dialog does not close until all questions are answered (manual takeover of the submit button validation); multiple pending items handled one by one (same as approvals)

## 0.4.9 (2026-08-21)
- Settings page footer shows the current version number (versionName + versionCode, from BuildConfig)

## 0.4.8 (2026-08-20)
- Fixed conversation order disorder: loadHistory reconciliation previously appended local echo/queued/active bubbles unconditionally at the end of the list, causing disorder when they were earlier than the latest history entries; changed to sort ascending by timeMillis (history uses created_at, queued/active bubbles use the queue API's created_at, local echo uses send time, items without a timestamp sort last) before setAll
- Api.listQueuedPrompts now also parses created_at: added QueuedPrompt(text, createdAt), PromptQueue.queued/active both changed to QueuedPrompt
- Fallback reconciliation when idle too: history polling is now always-on in the foreground (busy 15s / idle 60s, stopped on onPause, prevents stacking); badge states (queued/active/undelivered) of idle sessions no longer freeze after the WS stabilizes
- New reconciliation diagnostic logging (tag "Reconcile"): history count, queue/active summary, and the determination for each local echo (history confirmed/server queue/active/undelivered/POST in flight)

## 0.4.7 (2026-08-20)
- Fixed "entering a session during an in-progress turn shows no progress" (v0.37.2 server: WS clients that subscribe only after a turn starts do not receive that turn's transcript.ops, including streaming content and the turn.upsert completion event):
  - The transcript.reset branch parses payload.snapshot.meta.agent.phase for the realtime phase and calls back onPhase (for multiple resets from multiple agents, takes main: agent_id=="main" or the one carrying meta), showing "Working/Thinking" immediately upon entering a session (new tool_call phase display added)
  - Polls loadHistory every 15s while busy (started when turnActive, stopped on onPause, prevents stacking timers); detects busy disappearing during polling (data.active empty and queue cleared) -> immediately refreshes everything; onWorkChanged(false)/phase ended also trigger a fallback refresh. A completed in-progress turn's reply becomes visible within 15s, no longer relying on the unreceivable turn.upsert
- In-progress is no longer labeled "queued": GET /prompts?status=queued's data.active is the currently executing prompt (v0.37.2, not in queued[]); matched against local echo -> labeled "Active" in small text (ChatMsg gains an active field), and re-entering a session without echo renders an active-0 server bubble; active messages are not marked "undelivered"
- Api.listQueuedPrompts return type changed to PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- Fixed stale "Running" badge in the session list: auto loadAll() every 30s while resumed (stopped on onPause; loadAll is read-only and does not interrupt pull-to-refresh/typing)
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped): local echo that is neither in history nor in the server queue and has existed for more than 60s -> marked "Undelivered (dropped by server)" (red warning, no longer shows "Queued"); POST in flight (<60s) is retained normally
- ChatMsg gains an undelivered field; queued-* bubble behavior unchanged (rebuilt from the server queue each round, disappears naturally when absent from both queue and history)

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session/killing the app": the server queue is the source of truth; loadHistory also fetches GET /prompts?status=queued, and queued messages render as user bubbles with a small "Queued" label (deduplicated against local echo with the same text, showing only one copy)
- pendingLocal reconciliation upgrade: confirmed in history -> removed; present in the server queue -> rendered via the server queue bubble instead; neither in history nor queue (POST in flight) -> retained
- When the queue API fetch fails, degrades to the old behavior (retains all unconfirmed echoes) without blocking history loading
- When POST returns queued, the local echo is immediately marked "Queued" (MessageAdapter gains markQueued; ChatMsg gains a queued field)

## 0.4.3 (2026-08-20)
- Fixed "sent messages disappear": while busy the server returns queued and the message temporarily does not enter history, so loadHistory's setAll would wipe the optimistic echo -> maintains pendingLocal, appending unconfirmed echoes after the history, retained across reconnect/refresh; automatically confirmed and removed once a user message with the same text appears in history
- sendPrompt returns the server status: when queued, the status bar shows "Queued, waiting for the current task to finish..."
- On send failure, the optimistic echo is withdrawn (MessageAdapter gains removeById)
- New tool workflow stream: parses kind=tool frames of WS frame.upsert (display.summary ?: inputText ?: input, truncated to 80 characters), inserting small gray temporary entries into the message list (running / done), with the status bar showing "Working: tool name"; naturally cleared with loadHistory after the turn ends

## 0.4.2 (2026-08-15)
- Fixed "session loss": listSessions no longer sends busy=false, so running/pending-approval sessions are visible again, with a "Running" badge next to the title
- New tool approval: after entering a session, polls pending approvals every 5 seconds (foreground only), showing a dialog with tool name + summary, supporting approve/deny, handling multiple items one by one
- Default workspace changed to "last selection -> workspace with the largest session_count -> the first one" (the old logic hardcoded a Linux path, mistakenly landing in Downloads on Mac/iOS hosts)
- WorkspaceItem gains session_count parsing

## 0.4.1 (2026-08-15)
- Mode state mechanism aligned with the official web UI: persisted locally per session (SharedPreferences), with mode fields sent at the top level of prompts when sending messages (plan_mode/swarm_mode/permission_mode/model)
- Background: server v0.35.0 GET /profile does not return the real agent_config, so the echo is untrustworthy

## 0.4 (2026-08-15)
- Session mode bar: plan mode/Swarm toggle, permission mode (Manual/Auto/YOLO), model switch, goal mode (Set/Pause/Resume/Cancel)
- Fix: the cleartext HTTP whitelist was hardcoded to a single host address, preventing adding other hosts -> allow all (personal tailnet intranet)
- Fix: added a permanent "Server Settings" entry on the gate page, preventing lockout when the connection fails
- Fix: Tailscale launch (Android 11+ package visibility queries declaration) + system VPN settings fallback

## 0.3.2 (2026-08-15)
- Fixed user bubble text being horizontally truncated (layout constraint)
- Fixed the last message being covered by the input box (RecyclerView padding + scroll timing)
- Fixed history message order (the API returns newest first and must be reversed)

## 0.3 (2026-08-15)
- Filter out phantom user messages injected by the system (<system-reminder> / blocks starting with <cron-fire)

## 0.2 (2026-08-14)
- Material Design 3 polish: new icon (blue-purple gradient + bubble K), day/night dual themes, splash, chat bubble redesign, long-press to copy, code block rendering

## 0.1 (2026-08-14)
- First release: Tailscale gate, session list + workspace switching, WS streaming chat (transcript.ops), voice input (SpeechRecognizer zh-CN), multi-host profiles

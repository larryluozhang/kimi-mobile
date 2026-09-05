# Changelog

## 0.7.2 (2026-09-05)
- Paginated history loading, fixing tool-heavy sessions "showing only 3 messages": GET /sessions/{id}/messages returns only the latest 100 **raw** messages (including tool roles), so after filtering only a few visible bubbles may remain; Api.getMessages gains an optional beforeId parameter (before_id verified working), returning MessagesPage (visible messages + oldest raw message id anchor + raw count; hasMore roughly determined by whether the raw count reaches page_size=100)
- New "Load earlier messages" entry at the top of the chat page (shown only when more may exist; shows "Loading..." while loading): on tap, fetches the previous page using the current oldest raw message id as before_id and prepends the results into the list (earlier timestamps naturally sort first via the existing timeMillis-based reconcile, deduplicated against the latest page by id; queued/executing/undelivered bubbles unaffected); prepending preserves reading position instead of jumping to the bottom; loading 0 items shows "No earlier messages" and hides the entry; when an entire page is filtered out (all tool), the user is told they can keep loading

## 0.7.1 (2026-09-05)
- Fix context usage display: limit taken from WS maxContextTokens (adopted only when >0), otherwise fall back to 1048576 (1M, observed server snapshot value); display format fixed as "usage/limit (percentage)" (e.g. "Context 690/1000k (0%)"), percentage always shown; fmtTokens decimal formatting pinned to US locale, fixing display errors like "690.k"
- New "Fork from here": long-press a user bubble for a menu (Copy / Fork from here); fork logic: count the user messages n that are already in server history after this message → :fork full clone → :undo {"count":n} on the new session to trim everything after → jump to the new session (Api gains undoSession, POST /sessions/{id}:undo); status bar shows "Forking..." during the process, Toast on fork failure, on undo failure the new session is kept with a notice that content was not trimmed

## 0.7.0 (2026-09-05)
- Context usage display: new "Context 23.5k/1000k (2%)" to the right of the mode summary bar; data source priority: 1) WS transcript.reset snapshot meta.agent.contextTokens/maxContextTokens 2) transcript.ops meta.merge agent.contextTokens 3) GET /sessions/{id} usage.context_tokens/context_limit (may be all 0 in practice; adopted only when non-zero, fallback only); refreshed on busy polling (loadHistory) and WS events, hidden when no data
- New "Fork" button in the chat page header: same code path as the /fork command (Api.sessionAction("fork") → jump to the new session)
- New /rename (/title) command: takes all text after the first space as the new title, POST /sessions/{id}/profile top-level title field (not metadata.title); Toast usage hint when empty, Toast on success + top title updated; /help command list updated accordingly

## 0.6.2 (2026-09-05)
- Fix / commands matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (APK size reduced from ~213MB back to ~35MB): removed the bundled assets/models/ model; SpeechOnnx now loads from filesDir/models/zipformer-bilingual/ (usable only when all four files are present; OnlineRecognizer is passed a null assetManager to load by absolute path); the Settings page voice card gains a "Download offline model" button + editable download URL input (stored in Prefs voice_model_url, default GitHub Releases v0.6.1-models); HttpURLConnection downloads the zip on a background thread to filesDir/tmp then extracts it (java.util.zip, matching the 4-file set by filename, tolerating one directory level inside the zip), progress (downloaded MB/total MB) shown next to the button; ChatActivity integration: in onnx-forced mode with the model not downloaded, tapping the mic prompts "Please download the offline model in Settings first"; in auto mode a missing model silently falls back to system recognition
- Slash command support in the input box: intercept text starting with / before sending, exact match (case-insensitive) for /compact /archive /fork /abort /stop /new /help routed to the server session action POST /api/v1/sessions/{id}:{action} (Api.sessionAction) or local flows; /archive returns to the session list on success, /fork jumps to the new session on success, /new reuses the session-creation flow of the list page (workspace selection strategy consistent with SessionsActivity), /help shows a dialog listing commands; other text starting with / is sent as a regular prompt (consistent with the official behavior); commands run on background threads with results reported via Toast/status bar

## 0.6.0 (2026-09-05)
- New bundled offline speech recognition engine sherpa-onnx (1.13.7 local AAR, Apache-2.0): built-in Chinese-English bilingual streaming model zipformer-bilingual-2023-02-20 (int8 quantized, ~190MB in assets, not committed to git); AudioRecord 16kHz mono capture + streaming recognition, partial results shown in the status bar, final result appended to the input box on endpoint/stop; tapping the mic again during recognition ends it and flushes the trailing result
- New "Speech engine" option in Settings (auto/onnx/system, default auto): in auto, offline is preferred when the model exists, and a missing model or init failure automatically falls back to the system SpeechRecognizer (old path retained); onnx forces offline, system forces system
- Recording permission reuses the existing RECORD_AUDIO request logic

## 0.5.2 (2026-09-02)
- Question card/dialog content area is height-limited and scrollable: with many questions and options the submit button at the bottom is always reachable (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages on the sender side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages on the sender side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages on the sender side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- New question support (pending_interaction="question" scenario): when the server sends AskUserQuestion, ChatActivity polls GET /questions?status=pending every 5s (sharing the 5s polling timer with approvals); when a pending item exists a dialog is shown: each question renders a RadioGroup single-choice (label — description), and when allow_other, an "Other (custom answer)" + text input is appended; submit {"kind":"single","option_id":...} / {"kind":"other","text":...}; the "Skip" button sends {"kind":"skipped"}; the dialog does not close until all questions are answered (submit button validation taken over manually); multiple pending items are handled one by one (same as approvals)

## 0.4.9 (2026-08-21)
- Show the current version number at the bottom of the Settings page (versionName + versionCode, from BuildConfig)

## 0.4.8 (2026-08-20)
- Fix conversation order scrambling: loadHistory reconcile previously appended local echo/queued/executing bubbles unconditionally at the end of the list, scrambling the order when they were earlier than the latest history entry; now sorted ascending by timeMillis (history uses created_at, queued/active bubbles use the queue API's created_at, local echoes use the send time, entries without a timestamp go last) before setAll
- Api.listQueuedPrompts now also parses created_at: new QueuedPrompt(text, createdAt), PromptQueue.queued/active both changed to QueuedPrompt
- Fallback reconcile even when idle: history polling is now always-on while in the foreground (busy 15s / idle 60s, stopped in onPause, protected against stacking); after the WS stabilized, badge states (queued/executing/undelivered) of idle sessions no longer freeze
- New reconcile diagnostic logs (tag "Reconcile"): history count, queue/active summary, and the verdict for each local echo (history-confirmed/server-queue/executing/undelivered/POST-in-flight)

## 0.4.7 (2026-08-20)
- Fix "no progress visible when entering a session mid-turn" (v0.37.2 server: a WS client that subscribes mid-turn receives no transcript.ops for that turn, including streaming content and the turn.upsert completion event):
  - The transcript.reset branch parses payload.snapshot.meta.agent.phase for the live phase and calls back onPhase (among multiple resets from multiple agents, main is taken: agent_id=="main" or one carrying meta), immediately showing "Working/Thinking" on session entry (new tool_call phase display)
  - Poll loadHistory every 15s while busy (started when turnActive, stopped in onPause, protected against stacking timers); during polling, detect busy disappearing (data.active empty and queue cleared) → immediately do a unified refresh; onWorkChanged(false)/phase ended also trigger a fallback refresh. After an in-flight turn completes, the reply is visible within 15s, no longer relying on the unreceivable turn.upsert
- Executing is no longer labeled "queued": GET /prompts?status=queued's data.active is the currently executing prompt (v0.37.2, not in queued[]); when it matches a local echo → labeled "Executing" in small text (ChatMsg gains an active field), re-entering a session with no echo renders an active-0 server bubble; messages in active are not labeled "undelivered"
- Api.listQueuedPrompts return type changed to PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- Fix stale "Running" badge in the session list: auto loadAll() every 30s while resumed (stopped in onPause; loadAll is read-only and does not interrupt pull-to-refresh/typing)
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped): local echoes that are neither in history nor in the server queue and have existed for over 60s → labeled "Undelivered (dropped by server)" (red warning, no longer shown as "queued"); POSTs in flight (<60s) are kept as-is
- ChatMsg gains an undelivered field; queued-* bubble behavior unchanged (rebuilt from the server queue each round, naturally disappearing when present in neither queue nor history)

## 0.4.4 (2026-08-20)
- Fix "queued messages disappear after re-entering a session / killing the app": the server queue is now the source of truth; loadHistory also fetches GET /prompts?status=queued, and messages in the queue are rendered as user bubbles with a small "Queued" label (deduplicated by text against local echoes, only one copy shown)
- pendingLocal reconcile upgraded: confirmed in history → removed; present in the server queue → rendered by the server-queue bubble instead; in neither history nor queue (POST in flight) → kept
- When the queue API fetch fails, fall back to the old behavior (keep all unconfirmed echoes) without blocking history loading
- When POST returns queued, the local echo is immediately labeled "Queued" (MessageAdapter gains markQueued; ChatMsg gains a queued field)

## 0.4.3 (2026-08-20)
- Fix "sent messages disappearing": when busy, the server returns queued and the message does not enter history yet, so loadHistory's setAll would wipe the optimistic echo → maintain pendingLocal; unconfirmed echoes are appended after the history and survive reconnects/refreshes; once a user message with the same text appears in history, the echo is automatically confirmed and removed
- sendPrompt returns the server status: when queued, the status bar shows "Queued, waiting for the current task to finish..."
- On send failure, retract the optimistic echo (MessageAdapter gains removeById)
- New tool activity stream: parse kind=tool frames from WS frame.upsert (display.summary ?: inputText ?: input, truncated to 80 chars), inserting small gray temporary entries into the message list (running 🔧 / done ✓), status bar shows "Working: tool-name"; naturally cleared by loadHistory after the turn ends

## 0.4.2 (2026-08-15)
- Fix "lost sessions": listSessions no longer passes busy=false, so running/pending-approval sessions are visible again, with a "Running" label next to the title
- New tool approvals: after entering a session, poll pending approvals every 5 seconds (foreground only); a dialog shows the tool name + summary, supports approve/reject, and handles multiple items one by one
- Default workspace changed to "last selection → workspace with the largest session_count → the first one" (the old logic hardcoded a Linux path, incorrectly landing on Downloads on Mac/iOS hosts)
- WorkspaceItem now parses session_count

## 0.4.1 (2026-08-15)
- Mode state mechanism aligned with the official web UI: persisted locally per session (SharedPreferences), and mode fields (plan_mode/swarm_mode/permission_mode/model) are sent at the top level of prompts when sending messages
- Background: server v0.35.0 GET /profile does not return the real agent_config, so its echo is untrustworthy

## 0.4 (2026-08-15)
- Session mode bar: Plan mode/Swarm toggle, permission mode (manual/auto/YOLO), model switch, goal mode (set/pause/resume/cancel)
- Fix: the cleartext HTTP whitelist hardcoded the 146 address, making it impossible to add other hosts → allow globally (private tailnet use)
- Fix: added a persistent "Server settings" entry on the gate page to prevent lockout when the server is unreachable
- Fix: Tailscale launch (Android 11+ package visibility queries declaration) + fallback to system VPN settings

## 0.3.2 (2026-08-15)
- Fix user bubble text being horizontally truncated (layout constraint)
- Fix the last message being obscured by the input box (RecyclerView padding + scroll timing)
- Fix history messages in reverse order (the API returns newest first and must be reversed)

## 0.3 (2026-08-15)
- Filter out system-injected phantom user messages (blocks starting with <system-reminder> / <cron-fire)

## 0.2 (2026-08-14)
- Material Design 3 polish: new icon (blue-purple gradient + bubble K), day/night dual themes, splash screen, chat bubble redesign, long-press to copy, code block rendering

## 0.1 (2026-08-14)
- First release: Tailscale gate, session list + workspace switching, WS streaming chat (transcript.ops), voice input (SpeechRecognizer zh-CN), multi-host profiles

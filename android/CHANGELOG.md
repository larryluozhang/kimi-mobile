# Changelog

## 0.7.1 (2026-09-05)
- Fixed context usage display: the limit now comes from WS maxContextTokens (adopted only when >0), otherwise falls back to 1048576 (1M, the observed server snapshot value); the display format is fixed to "usage/limit (percent)" (e.g. "Context 690/1000k (0%)") and always includes the percentage; fmtTokens decimal formatting is pinned to the US locale, fixing display glitches like "690.k"
- Added "Fork from here": long-press a user bubble for a menu (Copy / Fork from here); fork logic: count n = number of user messages after this one that have entered the server-side history → :fork to fully clone → :undo {"count":n} on the new session to trim everything after it → jump to the new session (Api gains undoSession, POST /sessions/{id}:undo); a status bar shows "Forking..." during the process, fork failure shows a Toast, undo failure keeps the new session and warns that content was not trimmed

## 0.7.0 (2026-09-05)
- Context usage display: added "Context 23.5k/1000k (2%)" on the right side of the mode summary bar; data source priority: 1. WS transcript.reset snapshot meta.agent.contextTokens/maxContextTokens 2. transcript.ops meta.merge agent.contextTokens 3. GET /sessions/{id} usage.context_tokens/context_limit (observed to sometimes be all 0; adopted only when non-zero, fallback only); refreshed on busy polling (loadHistory) and WS events; hidden when no data
- Added a "Fork" button to the chat page header: same code path as the /fork command (Api.sessionAction("fork") → jump to the new session)
- Added /rename (/title) command: everything after the first space becomes the new title, POST /sessions/{id}/profile with a top-level title field (not metadata.title); empty input shows a usage Toast, success shows a Toast and updates the top title; the /help command list was updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / commands to match on the first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (APK size back down from ~213MB to ~35MB): removed the bundled assets/models/ model; SpeechOnnx now loads from filesDir/models/zipformer-bilingual/ (all four files must be present to be usable; OnlineRecognizer gets a null assetManager and loads by absolute path); the Settings speech card gains a "Download offline model" button + editable download URL field (stored in Prefs voice_model_url, defaulting to GitHub Releases v0.6.1-models); HttpURLConnection downloads the zip on a worker thread to filesDir/tmp then extracts it (java.util.zip, matching the 4-file set by filename, tolerating one wrapper directory inside the zip); progress (downloaded MB / total MB) shows next to the button; ChatActivity integration: in forced-onnx mode, tapping the mic without the model downloaded prompts "Please download the offline model in Settings first"; in auto mode a missing model silently falls back to system recognition
- Slash command support in the input field: text starting with / is intercepted before sending; exact matches (case-insensitive) for /compact /archive /fork /abort /stop /new /help go through server session actions POST /api/v1/sessions/{id}:{action} (Api.sessionAction) or local flows; /archive returns to the session list on success, /fork jumps to the new session on success, /new reuses the list page's session-creation flow (workspace selection strategy matches SessionsActivity), /help shows a command list dialog; other /-prefixed text is sent as a regular prompt (consistent with the official behavior); all commands run on worker threads with Toast/status bar feedback

## 0.6.0 (2026-09-05)
- Added a bundled offline speech recognition engine: sherpa-onnx (1.13.7 local AAR, Apache-2.0); built-in bilingual Chinese-English streaming model zipformer-bilingual-2023-02-20 (int8 quantized, ~190MB in assets, not committed to git); AudioRecord 16kHz mono capture + streaming recognition, partials shown in the status bar, final result appended to the input field on endpoint/stop; tapping the mic again while recognizing ends recognition and flushes the tail result
- Settings gains a "Speech engine" option (auto/onnx/system, default auto): in auto mode the offline engine is preferred when the model exists, with automatic fallback to the system SpeechRecognizer if the model is missing or initialization fails (old path retained); onnx forces offline, system forces the system engine
- Recording permission reuses the existing RECORD_AUDIO request logic

## 0.5.2 (2026-09-02)
- Question card/dialog content area is now height-capped and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the send side: the system-injection filter now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added question support (pending_interaction="question" scenario): when the server issues AskUserQuestion, ChatActivity polls GET /questions?status=pending every 5s (sharing the 5s poll timer with approvals); when a pending item exists, a dialog opens: each question renders a RadioGroup single-choice list (label — description), with an additional "Other (custom answer)" + text input when allow_other is set; submitting sends {"kind":"single","option_id":...} / {"kind":"other","text":...}; a "Skip" button sends {"kind":"skipped"}; the dialog cannot be dismissed until all questions are answered (manual validation wired to the submit button); multiple pending items are handled one by one (same as approvals)

## 0.4.9 (2026-08-21)
- The Settings page footer now shows the current version (versionName + versionCode, from BuildConfig)

## 0.4.8 (2026-08-20)
- Fixed out-of-order conversations: loadHistory reconciliation previously appended local echo / queued / executing bubbles unconditionally to the end of the list, scrambling the order when they were older than the latest history entry; now everything is sorted by timeMillis ascending (history uses created_at, queued/active bubbles use the queue endpoint's created_at, local echoes use the send time, items without a timestamp go last) before setAll
- Api.listQueuedPrompts now also parses created_at: added QueuedPrompt(text, createdAt); both PromptQueue.queued and active are now QueuedPrompt
- Fallback reconciliation also when idle: history polling now runs continuously in the foreground (15s while busy / 60s while idle, stopped in onPause to prevent stacking); once WS is stable, badge states (queued/executing/undelivered) of idle sessions no longer freeze
- Added reconciliation diagnostic logs (tag "Reconcile"): history count, queue/active summary, and the verdict for each local echo (confirmed in history / in server queue / executing / undelivered / POST in flight)

## 0.4.7 (2026-08-20)
- Fixed "no progress visible when entering a session mid-turn" (v0.37.2 server: a WS client that subscribes mid-turn receives none of that turn's transcript.ops, including streaming content and the turn.upsert completion event):
  - The transcript.reset branch parses payload.snapshot.meta.agent.phase for the real-time phase and calls back onPhase (among multiple resets from multiple agents, pick main: agent_id=="main" or one carrying meta), immediately showing "working/thinking" on session entry (new tool_call phase display)
  - Poll loadHistory every 15s while busy (started when turnActive, stopped in onPause to prevent stacking timers); when polling detects busy has cleared (data.active is empty and the queue is drained) → immediately do a unified refresh; onWorkChanged(false) / phase ended also trigger a fallback refresh. The reply becomes visible within 15s of the in-flight turn completing, no longer relying on the undeliverable turn.upsert
- "Executing" is no longer called "queued": GET /prompts?status=queued's data.active is the currently executing prompt (v0.37.2, not inside queued[]); when it matches a local echo → mark it with an "executing" sub-label (ChatMsg gains an active field); when re-entering a session without a local echo, render the active-0 server bubble; messages in active are never marked "undelivered"
- Api.listQueuedPrompts return type changed to PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- Fixed stale "running" badges in the session list: auto loadAll() every 30s while resumed (stopped in onPause; loadAll is read-only and does not interrupt pull-to-refresh or typing)
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom-busy, queued prompts are silently dropped): a local echo that is in neither history nor the server queue and has existed for over 60s → mark "undelivered (dropped by server)" (red warning, no longer shows "queued"); POSTs still in flight (<60s) are kept as-is
- ChatMsg gains an undelivered field; queued-* bubble behavior is unchanged (rebuilt from the server queue each round; disappears naturally when in neither queue nor history)

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session / killing the app": the server queue is now the source of truth; loadHistory also fetches GET /prompts?status=queued, and queued messages render as user bubbles with a "queued" sub-label (deduplicated against local echoes with identical text — only one copy is shown)
- pendingLocal reconciliation upgraded: confirmed in history → removed; present in the server queue → rendered by the server-queue bubble instead; in neither (POST in flight) → kept
- When the queue endpoint fetch fails, degrade to the old behavior (keep all unconfirmed echoes) without blocking history loading
- When POST returns queued, the local echo is immediately marked "queued" (MessageAdapter gains markQueued; ChatMsg gains a queued field)

## 0.4.3 (2026-08-20)
- Fixed "sent messages disappearing": when busy, the server returns queued and the message temporarily stays out of history, so loadHistory's setAll would wipe the optimistic echo → now maintains pendingLocal; unconfirmed echoes are appended after the history and survive reconnects/refreshes; they are confirmed and removed automatically once a user message with identical text appears in history
- sendPrompt returns the server status: when queued, the status bar shows "Queued, waiting for the current task to finish..."
- On send failure, the optimistic echo is withdrawn (MessageAdapter gains removeById)
- Added tool activity feed: parses WS frame.upsert frames with kind=tool (display.summary ?: inputText ?: input, truncated to 80 chars), inserting small gray temporary entries into the message list (running 🔧 / done ✓); the status bar shows "Working: tool name"; entries are cleared naturally with loadHistory when the turn ends

## 0.4.2 (2026-08-15)
- Fixed "sessions disappearing": listSessions no longer passes busy=false, so running/pending-approval sessions are visible again, with a "running" marker next to the title
- Added tool approvals: after entering a session, poll pending approvals every 5 seconds (foreground only); a dialog shows the tool name + summary with approve/reject options; multiple items handled one by one
- Default workspace changed to "last selected → workspace with the largest session_count → the first one" (the old logic hardcoded a Linux path and wrongly landed on Downloads on Mac/iOS hosts)
- WorkspaceItem now parses session_count

## 0.4.1 (2026-08-15)
- Mode state mechanism aligned with the official web UI: persisted locally per session (SharedPreferences); mode fields (plan_mode/swarm_mode/permission_mode/model) ride along at the top level of prompts when sending
- Background: server v0.35.0's GET /profile does not return the real agent_config, so its echo cannot be trusted

## 0.4 (2026-08-15)
- Session mode bar: plan mode / Swarm toggle, permission mode (manual/auto/YOLO), model switching, goal mode (set/pause/resume/cancel)
- Fix: the cleartext-HTTP whitelist hardcoded the 146 address, making it impossible to add other hosts → now allowed globally (personal use inside a tailnet)
- Fix: added a permanent "Server settings" entry on the gate page to avoid being locked out when the connection fails
- Fix: Tailscale launch (Android 11+ package visibility queries declaration) + system VPN settings as fallback

## 0.3.2 (2026-08-15)
- Fixed user bubble text being horizontally truncated (layout constraints)
- Fixed the last message being obscured by the input field (RecyclerView padding + scroll timing)
- Fixed history messages in reverse order (the API returns newest first and must be reversed)

## 0.3 (2026-08-15)
- Filter system-injected phantom user messages (blocks starting with <system-reminder> / <cron-fire)

## 0.2 (2026-08-14)
- Material Design 3 polish: new icon (blue-purple gradient + bubble K), day/night themes, splash screen, redesigned chat bubbles, long-press to copy, code block rendering

## 0.1 (2026-08-14)
- First release: Tailscale gating, session list + workspace switching, WS streaming chat (transcript.ops), voice input (SpeechRecognizer zh-CN), multi-host profiles

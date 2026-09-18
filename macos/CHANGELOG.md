# Changelog

## 0.7.4 (2026-09-18)
- In-app update reminder and auto-upgrade: app-latest channel (latest.json + assets, constant URL); throttled automatic check every 24h on launch plus "Check for Updates" in Settings; on macOS the DMG is downloaded (sha256 verified), then the app quits and automatically reinstalls into /Applications and restarts (standalone script, log at ~/.kimi-mobile/update.log); on Windows the download is fetched via the browser, extracted, and replaced (AppUpdater selects the macos/windows manifest node by os.name); callbacks are uniformly dispatched via Snapshot.withMutableSnapshot, fixing the snapshot race crash caused by writing Compose state from background threads
- Plain-language permission mode names: Confirm Every Step / Standard Auto / Full Autonomy (Dangerous) (values unchanged, unknown values fall through as-is); a ⚠️ warning pops up every time before selecting auto; when a session opens, the server field from meta identifies the backend and the yolo description switches per backend
- Approval card shows the full plan text (plan_review): ApprovalItem extended with displayKind/plan/options; ExitPlanMode approval renders the height-limited scrollable plan text inside the card + options single-choice + rejection note; the response body supports feedback/selected_label
- Fixed sessions not scrolling to the bottom when opened (same regression as on Android): unconditionally scroll to the bottom when the first content arrives (pendingInitialScroll), scrollListToEnd performs an overflow catch-up scroll aligned to the bottom edge of the last item; afterwards restores "follow only when at the bottom"
- "/" command completion menu: a filtered panel pops up above the input field (8 commands with descriptions), tapping an item fills in "/cmd "
- Explicit partial copy: the selection menu of assistant/tool bubbles is unified from Compose's default English Copy to a consistent "Copy" (onFork of forkTextContextMenu made nullable); user bubbles keep "Copy + Fork from Here"
- Per-session draft isolation: AppState.drafts (sessionId → draft, in-memory only); input changed to remember(sessionId) and cleared after sending — fixes the previous issue of a globally shared draft leaking across sessions
- New Windows version: the same Compose Multiplatform codebase, createDistributable produces a portable no-install package on the Windows build machine (jpackage, ~61MB zip with bundled JRE)

## 0.7.3 (2026-09-10)
- Image+text messages: a new "Image" button in the input area (AWT FileDialog for picking); the selected image is uploaded via
  POST /api/v1/files (multipart, field file) to obtain a file_id and staged for sending
  (the input area shows a thumbnail preview + remove button); on send, content mixes image and text
  ({"type":"image","source":{"kind":"file","file_id":"..."}} first, text block after)
- Bubbles with images display the image: Api.getMessages parses image blocks and extracts file_id (pure-image messages with no text also display);
  MessageBubble fetches the bytes on a background thread via GET /api/v1/files/{file_id} (with Bearer) and decodes them into an ImageBitmap,
  with a simple LRU in-memory cache (32 entries); local echo shows the local bytes directly before confirmation, no re-fetch needed
- Api.kt adds uploadFile / downloadFile; sendPrompt gains an optional imageFileId parameter;
  MiniHttp splits out binary send/receive (raw/getBytes/postBytes); the multipart request body is constructed by hand (no OkHttp)
- Model selector changed to a server-driven dynamic list: Api.kt adds listModels() (GET /api/v1/models, data.items[]
  contains provider/model/display_name/max_context_size); the model dropdown in the mode bar and the global model
  in Settings both use the dynamic list (display_name shown, model id as the value), fetched when entering a session or opening Settings,
  falling back to the original 4 built-in presets on failure or empty result
- Context limit follows the current model: the current model (session-level profile.model, or the global default if empty) maps to the model list's
  max_context_size as the limit; priority: current model's max_context_size > WS maxContextTokens
  > fallback 1048576; after a model switch the limit auto-updates with recomposition
- The global model in Settings changed from a free-text field to a dropdown (same data source as the mode bar)
- Auto-refresh no longer yanks the reading position: bottom-following only animateScrollToItem's to the end when the user is already near the bottom
  (with a 1-item tolerance), otherwise no scrolling is performed at all (keyed LazyColumn naturally preserves the viewport)

## 0.7.2 (2026-09-05)
- Paginated history loading: GET /messages returns only the latest 100 raw messages (including tool), so the visible bubbles after filtering may be few;
  a "Load Earlier Messages" button is added at the top of the chat page, paging backward with before_id=<current oldest message id> and prepending
  (reconcileHistory sorts by time and is naturally compatible; pendingEchoes/queued/active reconciliation is unaffected;
  keyed LazyColumn keeps the scroll position from jumping on prepend)
- Loading state hints: spinner + "Loading…" while loading; after reaching the top (this page's raw item count is below page_size) shows "No more";
  full-history refreshes on WS reconnect/busy end keep the already-loaded old pages (AppState.olderHistory merged and deduplicated by id)
- Api.getMessages gains an optional beforeId parameter, returning MessagesPage(messages, hasMore)

## 0.7.1 (2026-09-05)
- Fixed the right-click menu showing only Copy: SelectionContainer's built-in text menu shadowed the fork item; switched to LocalTextContextMenu to provide a custom menu (Copy + Fork from Here)
- Context usage display fix: fixed format "usage/limit (percent)", with the percentage in all cases;
  the limit is taken from WS maxContextTokens (used only when >0), otherwise the fallback is 1048576 (1M);
  decimal formatting pinned to Locale.US, fixing "690.k"-style format errors seen under some locales
- Fork from a message: the user bubble right-click menu adds "Fork from Here" → counts the n user messages after that message
  (undelivered echoes are not counted) → :fork full clone → :undo {"count":n} on the new session → switch to the new session;
  undo failure (e.g. 40911 nothing to undo) does not block the switch and is reported truthfully; /help updated accordingly
- Api.kt adds undoSession (POST /sessions/{id}:undo, body count field)

## 0.7.0 (2026-09-05)
- Context usage display: shown next to the session header title, e.g. "23.5k/1000k (2%)"; data source priority:
  1) contextTokens/maxContextTokens of payload.snapshot.meta.agent in the WS transcript.reset snapshot (taken at the same level as phase);
  2) agent.contextTokens from transcript.ops meta.merge (handled independently of phase, taken even when there is no phase);
  3) fallback GET /sessions/{id} usage.context_tokens/context_limit (observed to possibly be all 0 in practice; all-0 is treated as no data and not shown);
  reset on session switch, WS reports override the fallback value
- Topic fork button: a "Fork" button added to the session header toolbar, taking the same path as the /fork command
  (Api.forkSession + switch to the new session on success; the /fork branch refactored to reuse the same doFork)
- /rename (or /title) command: POST /sessions/{id}/profile, top-level title field in the body;
  all remaining text after the first space becomes the new title, empty shows usage; sidebar/title refreshed on success; /help updated accordingly
- Api.kt adds renameSession / getSessionUsage

## 0.6.2 (2026-09-05)
- Fixed / commands matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- The input field supports / slash commands (intercepted before sending, exact match case-insensitive; unrecognized /-prefixed text is still sent as a normal prompt):
  /compact → POST /sessions/{id}:compact to compress history (empty history returns server error 40910, reported as an error bubble);
  /archive → POST :archive, then clears activeSessionId and refreshes the sidebar;
  /fork → POST :fork, switching to the returned new session on success;
  /abort (or /stop) → POST :abort to interrupt the current turn; /new → same flow as the sidebar "New Session"; /help → pops up the command help
- Api.kt adds compactSession / archiveSession / forkSession (fork returns the new session id)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area is height-limited and scrollable: the submit button at the bottom stays reachable even with many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also matches the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also matches the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also matches the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Approval UI: polls GET /sessions/{id}/approvals?status=pending (every 5s); when the agent is blocked on approval an approval card is shown (tool name/action/summary + Approve/Reject buttons),
  POST /approvals/{approval_id} submits the decision (approved/rejected); when the agent initiates an approval-requiring tool call under manual permission it suspends, and the server resumes after the local decision
- Q&A UI: polls GET /sessions/{id}/questions?status=pending (every 5s); renders questions[] as a single-choice RadioButton group, appending "Other" + a text input when allow_other,
  plus a "Skip" button; answers is a map of question id → answer object (kind=single with option_id / other with text / skipped);
  after POST /questions/{question_id} the agent resumes and the turn continues
- Interrupt button: when busy, the chat header shows a "Stop" button, calling POST /sessions/{id}:abort (observed to return {"aborted":true}) to interrupt the current turn

## 0.4.9 (2026-08-21)
- The Settings window bottom shows the current version number: the version in build.gradle.kts is the single source of truth,
  written by the generateVersionProperties task into the version.properties resource at build time,
  and read from the classpath by AppVersion at runtime (shows "unknown" on read failure)

## 0.4.8 (2026-08-20)
- Fixed out-of-order conversation: echo/queued/running bubbles were previously appended unconditionally to the end of the list; they now carry timestamps
  (ChatMessage.timeMillis; Api.QueuedPrompt carries created_at), and reconcile output is sorted by time (oldest → newest)
- Badge inconsistency investigation: reconcileHistory adds RECONCILE diagnostic logging (reconcile inputs + the verdict for each echo);
  refreshHistory/session-entry loading adds cross-session race protection (results discarded if the user switched away during the fetch)
- Idle sessions are also reconciled every 60s as a fallback (still 15s when busy) — after the WS keepalive fix there are no more reconnect refreshes,
  so the queued/running/undelivered states of idle sessions can only stay fresh via periodic reconciliation

## 0.4.7 (2026-08-20)
- Fixed opening a running session showing a dead UI (observed on v0.37.2: a WS subscribed only mid-turn receives none of that turn's
  transcript.ops, not even the turn.upsert completion event):
  WsClient parses the real-time phase from payload.snapshot.meta.agent.phase when handling transcript.reset
  (running/streaming/tool_call/ended…; with multiple agents and multiple resets only the main agent is taken —
  agent_id=="main" or no agent_id with non-empty meta), and MainScreen immediately lights the "Working" state and sets busy accordingly
- Polls history every 15s while busy (including queue reconciliation): replies become visible within 15s when the in-flight turn completes,
  no longer relying on the undeliverable turn.upsert; onWorkChanged(false) immediately performs a final alignment;
  when polling finds the server has no active/queued left (busy is gone but the event was missed) it clears busy and performs a final alignment;
  polling is a LaunchedEffect(sessionId), so the same session never stacks multiple timers
- "Running" is no longer called "Queued": data.active of GET /prompts?status=queued is the currently executing prompt
  (since v0.37.2 it is not in queued[]); Api.listQueuedPrompts returns PromptQueue(queued, active);
  during reconciliation an active entry whose text matches a local echo is marked "Running" (same styling as "Queued"), and messages in active are never marked "Undelivered";
  active entries with no local echo (submitted from other clients) are rebuilt as "Running" bubbles

## 0.4.6 (2026-08-20)
- Fixed stale sidebar busy badges: the session list only refreshed on entering a session/turn end, so other sessions' "Running" marks stayed in the old state;
  MainScreen adds a 30s timer periodically calling loadSidebar (skipped while historyLoading or mid-load, without interrupting the current UI),
  and the sidebar is also refreshed once on WS onOpen reconnect success
- Fallback for queued messages dropped by the server (upstream MoonshotAI/kimi-code#3127: queued prompts silently dropped under phantom busy):
  PendingEcho records its creation timestamp; during reconciliation, an echo found neither in history nor in the server queue and older than 60s is marked
  "Undelivered (dropped by server)" as a red warning, no longer showing the fake "Queued" state; in-flight POSTs (<60s) are kept as-is, and queued state follows the server queue;
  queue-rebuilt bubbles (queued-$sessionId-hash) simply disappear when absent from both this round's queue and history (if truly queued they will be rebuilt next round)

## 0.4.5 (2026-08-20)
- Fixed the WS being disconnected and reconnected by the watchdog every ~40s (tens of thousands of times in total, each reconnect pulling the full history):
  observed on v0.37.2 that the server sends no heartbeat ping on non-loopback (Tailscale IP) connections,
  so the original passive liveness check of disconnecting after 35s without packets did not apply; changed to actively sending a protocol-level ping (0x9) after 15s idle,
  judging the connection dead and reconnecting only if no frame at all arrives within 35s (verified with the --wsprobe probe that the connection stays stable)

## 0.4.4 (2026-08-20)
- Fixed queued echoes being permanently wiped when switching sessions: pendingEchoes isolated by sessionId, LaunchedEffect no longer blindly clears
- Server queue as the source of truth: new GET /sessions/{id}/prompts?status=queued (Api.listQueuedPrompts);
  history refreshes (entering a session, turn end, WS reconnect) also fetch the queue and reconcile —
  echoes confirmed in history are removed; those in the queue are shown as "Queued" bubbles (local echoes and queue entries with the same text are deduplicated to one);
  echoes in neither history nor queue (POST in flight) are kept; messages submitted from other clients or queued before a restart can also be rebuilt as "Queued" bubbles
- Queued user bubbles carry a small "Queued" marker

## 0.4.3 (2026-08-20)
- Fixed queued echoes being wiped: sendPrompt returns status (running/queued); local echoes are registered as pendingEchoes,
  removed upon text confirmation during history refreshes (turn end, WS reconnect), with unconfirmed ones kept at the end of the list; echoes are removed on send failure
- The header status area shows "Queuing…" when queued
- Tool stream visible: frames with kind="tool" in WS frame.upsert render as tool activity entries (🔧 name: summary, marked ✓ when done),
  summary taken from display.summary ?: inputText ?: input, newlines stripped and truncated to 80 characters

## 0.4.2 (2026-08-15)
- Fixed session loss: listSessions no longer passes busy=false (which filtered out running/approval-blocked sessions)
- The session list shows a "Running" marker for busy=true (small dot + label)

## 0.4 (2026-08-15)
- Session mode bar: Plan/Swarm toggle, permission mode, model dropdown, goal mode (create/pause/resume/cancel)
- Mode state implemented per the official mechanism: per-session local persistence (config.properties), mode fields carried at the top level of prompts
- Server discovery: GET /profile does not return the real agent_config (v0.35.0 hardcoded empty shell)
- New --e2e-profile self-check entry

## 0.3.1 (2026-08-15)
- Fixed reversed history order (the API returns newest first, needs reversing)
- Enter to send / Shift+Enter for newline

## 0.3 (2026-08-15)
- Business errors (HTTP 200 wrapping code!=0) shown as red error bubbles
- Phantom message filtering (system-injected user messages such as <system-reminder>)
- WS frame-level logging; --e2e end-to-end self-check

## 0.2 (2026-08-14)
- Full-pipeline file logging at ~/.kimi-mobile/app.log
- Gated retry loop catching Throwable; on launch the most recent session is auto-selected and the WS established

## 0.1 (2026-08-14)
- First release (Compose Multiplatform Desktop)
- Handwritten NIO transport layer (MiniHttp/WsClient) working around the JVM network stack defect of the local OCLP setup (java.net/OkHttp connects but reads no data)
- Tailscale gating, sessions/workspaces, WS streaming chat, multi-host profiles

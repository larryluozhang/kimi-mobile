# Changelog

## 0.7.3 (2026-09-10)
- Image messages: added an "Image" button to the input area (AWT FileDialog picker); the selected image is uploaded via
  POST /api/v1/files (multipart, field file) to obtain a file_id that is held pending send
  (the input area shows a thumbnail preview with a remove button); on send, content mixes image and text
  ({"type":"image","source":{"kind":"file","file_id":"..."}} first, text block after)
- Bubbles with images render the image: Api.getMessages parses image blocks to extract file_id (image-only messages with no text are also displayed);
  MessageBubble fetches bytes on a background thread via GET /api/v1/files/{file_id} (with Bearer) and decodes them into an ImageBitmap,
  with a simple in-memory LRU cache (32 entries); local echoes display the local bytes directly before confirmation, no refetch needed
- Api.kt adds uploadFile / downloadFile; sendPrompt gains an optional imageFileId parameter;
  MiniHttp splits out binary send/receive (raw/getBytes/postBytes); multipart request bodies are constructed manually (no OkHttp)
- Model selector switched to a server-driven dynamic list: Api.kt adds listModels() (GET /api/v1/models, data.items[]
  containing provider/model/display_name/max_context_size); both the mode-bar model dropdown and the settings-page global model
  now use the dynamic list (display_name shown, model id as the value), fetched on entering a session or opening settings,
  falling back to the original 4 built-in presets on failure or empty result
- Context limit follows the current model: the current model (session-level profile.model, or the global default when empty) is mapped to the model list's
  max_context_size as the limit; precedence: current model max_context_size > WS maxContextTokens
  > fallback 1048576; the limit updates automatically on recomposition after a model switch
- Settings-page global model changed from a free-text field to a dropdown (same data source as the mode bar)
- Auto-refresh no longer yanks the reading position: bottom-following only animateScrollToItem to the bottom when the user is already near the bottom (1-item tolerance),
  otherwise no scrolling at all (keyed LazyColumn naturally preserves the viewport)

## 0.7.2 (2026-09-05)
- Paginated loading of history messages: GET /messages returns only the latest 100 raw messages (including tool messages), so visible bubbles after filtering may be few;
  added a "Load earlier messages" button at the top of the chat page, paging backward with before_id=<oldest current message id> and prepending
  (reconcileHistory sorts by time and is naturally compatible; pendingEchoes/queued/active reconciliation is unaffected;
  keyed LazyColumn prepending keeps the scroll position stable)
- Loading state indicators: spinner + "Loading..." while loading, and "No more messages" once the top is reached (raw items in this page fewer than page_size);
  full history refreshes on WS reconnect/busy end retain already-loaded older pages (AppState.olderHistory merged with dedup by id)
- Api.getMessages gains an optional beforeId parameter and returns MessagesPage(messages, hasMore)

## 0.7.1 (2026-09-05)
- Fixed right-click menu offering only Copy: SelectionContainer's built-in text menu was shadowing the fork item; now uses LocalTextContextMenu to provide a custom menu (Copy + Fork from here)
- Context usage display fix: format fixed to "usage/limit (percentage)", with the percentage always shown;
  the limit comes from WS maxContextTokens (only used when >0), otherwise falls back to 1048576 (1M);
  decimal formatting pinned to Locale.US, fixing "690.k"-style format errors under certain locales
- Fork from a specific message: the user bubble right-click menu adds "Fork from here" -> counts the number n of user messages after that message
  (undelivered echoes not counted) -> :fork full clone -> :undo {"count":n} on the new session -> switch to the new session;
  undo failure (e.g. 40911 nothing to undo) does not block the switch and is reported faithfully; /help updated accordingly
- Api.kt adds undoSession (POST /sessions/{id}:undo, body count field)

## 0.7.0 (2026-09-05)
- Context usage display: shows "23.5k/1000k (2%)" next to the session header title; data source precedence:
  1) contextTokens/maxContextTokens from payload.snapshot.meta.agent of the WS transcript.reset snapshot (taken at the same level as phase);
  2) agent.contextTokens from transcript.ops meta.merge (handled independently of phase, taken even without phase);
  3) fallback usage.context_tokens/context_limit from GET /sessions/{id} (observed to be all zeros in practice; all zeros treated as no data and not shown);
  reset on session switch; WS reports override fallback values
- Topic fork button: added a "Fork" button to the session header toolbar, sharing the same path as the /fork command
  (Api.forkSession + switch to the new session on success; the /fork branch refactored to reuse the same doFork)
- /rename (or /title) command: POST /sessions/{id}/profile with a top-level title field in the body;
  takes all remaining text after the first space as the new title, shows usage if empty, refreshes the sidebar/title on success; /help updated accordingly
- Api.kt adds renameSession / getSessionUsage

## 0.6.2 (2026-09-05)
- Fixed / commands matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Input box supports / slash commands (intercepted before send, exact match case-insensitive; unrecognized /-prefixed text is still sent as a normal prompt):
  /compact -> POST /sessions/{id}:compact to compress history (empty history returns server error 40910, shown as an error bubble);
  /archive -> POST :archive, then clears activeSessionId and refreshes the sidebar;
  /fork -> POST :fork, then switches to the returned new session on success;
  /abort (or /stop) -> POST :abort to interrupt the current turn; /new -> same flow as the sidebar "New session"; /help -> shows a command help popup
- Api.kt adds compactSession / archiveSession / forkSession (fork returns the new session id)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area is height-limited and scrollable: the submit button at the bottom is always reachable when there are many questions and options (all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (all three platforms)
## 0.5.0 (2026-09-02)
- Approval UI: polls GET /sessions/{id}/approvals?status=pending (every 5s); when the agent is blocked on approval, shows an approval card (tool name/action/summary + approve/reject buttons),
  POST /approvals/{approval_id} submits the decision (approved/rejected); when the agent issues an approval-required tool call under manual permission mode it suspends, and the server resumes execution after the local decision
- Q&A UI: polls GET /sessions/{id}/questions?status=pending (every 5s); renders questions[] as a single-choice RadioButton group, appends an "Other" option with a text input when allow_other,
  plus a "Skip" button; answers is a map of question id -> answer object (kind=single with option_id / other with text / skipped);
  after POST /questions/{question_id} the agent resumes execution and the turn continues
- Interrupt button: shows a "Stop" button in the chat header while busy, calling POST /sessions/{id}:abort (observed to return {"aborted":true}) to interrupt the current turn

## 0.4.9 (2026-08-21)
- Settings window shows the current version number at the bottom: the version in build.gradle.kts is the single source of truth,
  written into the version.properties resource at build time by the generateVersionProperties task,
  and read from the classpath at runtime by AppVersion (shows "unknown" if reading fails)

## 0.4.8 (2026-08-20)
- Fixed out-of-order conversation: echo/queued/running bubbles were previously appended unconditionally to the end of the list; they now carry timestamps
  (ChatMessage.timeMillis; Api.QueuedPrompt carries created_at), and reconcile output is sorted by time (oldest to newest)
- Badge inconsistency investigation: reconcileHistory adds RECONCILE diagnostic logging (reconciliation inputs + the verdict for each echo);
  refreshHistory/session-entry loading adds cross-session race protection (discards the result if the user switched away during the fetch)
- Idle sessions are also reconciled once every 60s as a fallback (still 15s while busy) -- after the WS keepalive fix there is no reconnect-triggered refresh,
  so the queued/running/undelivered states of idle sessions can only stay fresh via periodic reconciliation

## 0.4.7 (2026-08-20)
- Fixed a dead-looking UI when opening a running session (observed on v0.37.2: a WS subscribed mid-turn receives none of that turn's
  transcript.ops, not even the turn.upsert completion event):
  WsClient parses payload.snapshot.meta.agent.phase as the live phase when handling transcript.reset
  (running/streaming/tool_call/ended...; with multiple agents and multiple resets only the main agent is used --
  agent_id=="main" or no agent_id with non-empty meta), and MainScreen immediately shows the "working" state and sets busy accordingly
- Polls history every 15s while busy (including queue reconciliation): a reply from a completing turn is visible within 15s,
  no longer depending on the unreachable turn.upsert; onWorkChanged(false) immediately performs a final alignment;
  when polling finds no active/queued on the server (busy cleared but events missing), it clears busy and performs a final alignment;
  polling is a LaunchedEffect(sessionId), so the same session never stacks multiple timers
- Running is no longer labeled "queued": data.active from GET /prompts?status=queued is the currently executing prompt
  (since v0.37.2 it is not in queued[]); Api.listQueuedPrompts returns PromptQueue(queued, active);
  during reconciliation, active text matching a local echo -> marked "running" (same styling as "queued"); messages in active are never marked "undelivered";
  active entries without a local echo (submitted from another client) are reconstructed as "running" bubbles

## 0.4.6 (2026-08-20)
- Fixed stale busy badges in the sidebar: the session list only refreshed on entering a session or at turn end, so other sessions' "running" markers stayed in an old state;
  MainScreen adds a 30s timer that periodically calls loadSidebar (skipped during historyLoading/mid-load so it does not interrupt the current UI),
  and the sidebar is also refreshed on WS onOpen reconnect success
- Fallback for queued messages silently dropped by the server (upstream MoonshotAI/kimi-code#3127: queued prompts silently dropped under phantom busy):
  PendingEcho records its creation timestamp; during reconciliation, an echo that is neither in history nor in the server queue and is over 60s old -> marked "undelivered (dropped by server)"
  as a red warning, no longer showing a fake "queued" state; in-flight POSTs (<60s) are kept as-is; queued state is authoritative from the server queue;
  queue-reconstructed bubbles (queued-$sessionId-hash) disappear directly when absent from both this round's queue and history (if truly queued they are rebuilt next round)

## 0.4.5 (2026-08-20)
- Fixed the WS watchdog disconnecting and reconnecting every ~40s (tens of thousands of times in total, each reconnect pulling full history):
  observed on v0.37.2 that the server does not send heartbeat pings on non-loopback (Tailscale IP) connections,
  so the original passive liveness check of disconnecting after 35s without packets did not apply; changed to actively sending a protocol-level ping (0x9) after 15s idle,
  and only declaring the connection dead after 35s without any frame (verified with the --wsprobe probe that the connection stays stable)

## 0.4.4 (2026-08-20)
- Fixed queued echoes being permanently wiped on session switch: pendingEchoes are now isolated by sessionId, and LaunchedEffect no longer blindly clears them
- Server queue as the source of truth: added GET /sessions/{id}/prompts?status=queued (Api.listQueuedPrompts);
  on history refresh (entering a session, turn end, WS reconnect) the queue is also fetched and reconciled --
  echoes confirmed in history are removed; those in the queue are shown as "queued" bubbles (local echoes and queue entries with the same text are deduplicated to show only one);
  echoes in neither history nor queue (in-flight POSTs) are kept; messages submitted from other clients or queued before a restart can also be reconstructed as "queued" bubbles
- Queued user bubbles carry a small "queued" badge

## 0.4.3 (2026-08-20)
- Fixed queued echoes being wiped: sendPrompt returns a status (running/queued); local echoes are registered as pendingEchoes,
  removed on history refresh (turn end, WS reconnect) when confirmed by text, and unconfirmed ones are kept at the end of the list; send failures remove the echo
- Header status area shows "Queued..." while queued
- Tool activity stream visible: frames with kind="tool" in WS frame.upsert are rendered as tool activity entries (name: summary, with a checkmark when done);
  the summary takes display.summary ?: inputText ?: input, with newlines stripped and truncated to 80 characters

## 0.4.2 (2026-08-15)
- Fixed missing sessions: listSessions no longer passes busy=false (running/approval-blocked sessions were being filtered out)
- Session list shows a "running" marker (small dot + label) for busy=true

## 0.4 (2026-08-15)
- Session mode bar: plan/Swarm toggle, permission mode, model dropdown, goal mode (create/pause/resume/cancel)
- Mode state implemented per the official mechanism: persisted locally per session (config.properties), with mode fields carried at the top level of prompts
- Server finding: GET /profile does not return the real agent_config (v0.35.0 hardcodes an empty shell)
- Added the --e2e-profile self-check entry

## 0.3.1 (2026-08-15)
- Fixed history messages in reverse order (the API returns newest first; reversal needed)
- Enter to send / Shift+Enter for newline

## 0.3 (2026-08-15)
- Business errors (HTTP 200 wrapping code!=0) shown as red error bubbles
- Phantom message filtering (<system-reminder> and other system-injected user messages)
- WS frame-level logging; --e2e full-pipeline self-check

## 0.2 (2026-08-14)
- Full-pipeline file logging at ~/.kimi-mobile/app.log
- Gated retry loop catches Throwable; on startup, automatically selects the most recent session and establishes the WS

## 0.1 (2026-08-14)
- First release (Compose Multiplatform Desktop)
- Hand-written NIO transport layer (MiniHttp/WsClient) to work around the JVM network stack defect caused by the local OCLP patch (java.net/OkHttp connect but cannot read data)
- Tailscale gating, sessions/workspaces, WS streaming chat, multi-host profiles

# Changelog

## 0.7.3 (2026-09-10)
- Image+text messages: the input area gains an "Image" button (AWT FileDialog picker); the picked image is
  uploaded via POST /api/v1/files (multipart, field file) to obtain a file_id, held pending send
  (the input area shows a thumbnail preview + remove button); on send, the content interleaves image and text
  ({"type":"image","source":{"kind":"file","file_id":"..."}} first, text block after)
- Bubbles with images render the image: Api.getMessages parses image blocks and extracts file_id (pure-image
  messages with no text are also shown); MessageBubble fetches the bytes on a background thread via
  GET /api/v1/files/{file_id} (with Bearer) and decodes them into an ImageBitmap, with a simple LRU in-memory
  cache (32 images); local echoes show the local bytes directly before confirmation, no refetch needed
- Api.kt gains uploadFile / downloadFile; sendPrompt gains an optional imageFileId parameter;
  MiniHttp splits out binary send/receive (raw/getBytes/postBytes); the multipart request body is hand-built
  (no OkHttp)
- Model picker switched to a dynamic server-side list: Api.kt gains listModels() (GET /api/v1/models, data.items[]
  containing provider/model/display_name/max_context_size); both the mode-bar model dropdown and the settings page
  global model now use the dynamic list (display_name for display, model id as value), fetched when entering a
  session / opening the settings page, falling back to the original 4 built-in presets on failure or empty
- Context limit follows the current model: the current model (session-level profile.model, or the global default
  if empty) maps to the model list's max_context_size as the limit; priority: current model max_context_size > WS
  maxContextTokens > fallback 1048576; the limit updates automatically with recomposition after a model switch
- Settings page global model changed from a free-text field to a dropdown (same data source as the mode bar)

## 0.7.2 (2026-09-05)
- History message pagination: GET /messages returns only the latest 100 raw messages (including tool), so after
  filtering very few visible bubbles may remain; added a "Load earlier messages" button at the top of the chat page,
  which pages backward with before_id=<current oldest message id> and prepends (reconcileHistory's time-based
  sorting is naturally compatible; pendingEchoes/queued/active reconciliation is unaffected; the keyed LazyColumn
  keeps the scroll position steady when prepending)
- Loading state hints: spinner + "Loading…" while loading; after reaching the top (the page's raw items count is
  below page_size) shows "No more"; full history refreshes triggered by WS reconnect/busy end keep the already
  loaded older pages (AppState.olderHistory merged with id-based dedup)
- Api.getMessages gains an optional beforeId parameter, returning MessagesPage(messages, hasMore)

## 0.7.1 (2026-09-05)
- Fixed the right-click menu showing only Copy: SelectionContainer's built-in text menu shadowed the fork item;
  switched to LocalTextContextMenu to provide a custom menu (Copy + Fork from here)
- Context usage display fix: format fixed as "usage/limit (percent)", percent always shown; the limit takes WS
  maxContextTokens (used only when >0), otherwise falls back to 1048576 (1M); decimal formatting pinned to
  Locale.US, fixing "690.k"-style formatting errors under certain locales
- Fork from a message: the user bubble right-click menu gains "Fork from here" → counts the user messages n after
  this message (undelivered echoes not counted) → :fork full clone → :undo {"count":n} on the new session → switch
  to the new session; undo failure (e.g. 40911 nothing to undo) does not block the switch and is reported
  faithfully; /help updated accordingly
- Api.kt gains undoSession (POST /sessions/{id}:undo, body count field)

## 0.7.0 (2026-09-05)
- Context usage display: shows "23.5k/1000k (2%)" next to the session header title; data source priority:
  1) contextTokens/maxContextTokens from the WS transcript.reset snapshot payload.snapshot.meta.agent (taken at
  the same level as phase); 2) agent.contextTokens from transcript.ops meta.merge (handled independently of phase,
  taken even without phase); 3) fallback GET /sessions/{id} usage.context_tokens/context_limit (observed to
  possibly be all 0 — all-0 is treated as no data and not shown); reset on session switch; WS reports override
  the fallback value
- Topic fork button: the session header toolbar gains a "Fork" button, sharing the same path as the /fork command
  (Api.forkSession + switch to the new session on success; the /fork branch refactored to reuse the same doFork)
- /rename (or /title) command: POST /sessions/{id}/profile with a top-level title field in the body; takes all
  remaining text after the first space as the new title, shows usage when empty, refreshes the sidebar/title on
  success; /help updated accordingly
- Api.kt gains renameSession / getSessionUsage

## 0.6.2 (2026-09-05)
- Fixed / command matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Input field supports / slash commands (intercepted before sending, exact match case-insensitive; unrecognized
  text starting with / is still sent as a regular prompt): /compact → POST /sessions/{id}:compact to compress
  history (the server reports 40910 for empty history, surfaced as an error bubble); /archive → POST :archive,
  clears activeSessionId and refreshes the sidebar after archiving; /fork → POST :fork, switches to the returned
  new session on success; /abort (or /stop) → POST :abort to interrupt the current turn; /new → same flow as the
  sidebar "New session"; /help → pops up the command reference
- Api.kt gains compactSession / archiveSession / forkSession (fork returns the new session id)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area height-limited and scrollable: the submit button at the bottom stays reachable when
  there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isPhantomUserText adds the
  <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isPhantomUserText adds the
  <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isPhantomUserText adds the
  <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Approval UI: polls GET /sessions/{id}/approvals?status=pending (every 5s); when the agent is blocked on approval,
  shows an approval card (tool name/action/summary + approve/reject buttons); POST /approvals/{approval_id}
  submits the decision (approved/rejected); when an agent under manual permission initiates a tool call that
  requires approval it is suspended, and the server resumes after this client decides
- Q&A UI: polls GET /sessions/{id}/questions?status=pending (every 5s), renders questions[] as RadioButton
  single-choice groups; when allow_other, appends an "Other" option + text input field; a "Skip" button; answers
  is a map of question id → answer object (kind=single with option_id / other with text / skipped); after POST
  /questions/{question_id} is submitted the agent resumes and the turn continues
- Interrupt button: when busy, the chat header shows a "Stop" button, calling POST /sessions/{id}:abort (observed
  to return {"aborted":true}) to interrupt the current turn

## 0.4.9 (2026-08-21)
- The settings window bottom shows the current version number: the version in build.gradle.kts is the single
  source of truth, written at build time by the generateVersionProperties task into the version.properties
  resource, and read at runtime by AppVersion from the classpath (shows "unknown" if reading fails)

## 0.4.8 (2026-08-20)
- Fixed conversation order corruption: echo/queued/running bubbles were previously appended unconditionally at the
  end of the list; they now carry timestamps (ChatMessage.timeMillis; Api.QueuedPrompt carries created_at) and
  the reconcile output is sorted by time (old → new)
- Badge corruption investigation: reconcileHistory gains RECONCILE diagnostic logs (reconciliation input + the
  verdict for each echo); refreshHistory/session-entry loading gains cross-session race protection (results are
  discarded if the user switched sessions during the fetch)
- Idle sessions also get a fallback reconciliation every 60s (still 15s when busy) — after the WS keepalive fix
  there is no more reconnect refresh, so the queued/running/undelivered states of idle sessions can only stay
  fresh via periodic reconciliation

## 0.4.7 (2026-08-20)
- Fixed opening a running session showing a dead-silent UI (observed on v0.37.2: a WS client that subscribes
  mid-turn receives none of that turn's transcript.ops — not even the turn.upsert completion event): WsClient,
  when handling transcript.reset, parses payload.snapshot.meta.agent.phase for the real-time phase
  (running/streaming/tool_call/ended…; among multiple resets from multiple agents, only the main agent is taken —
  agent_id=="main" or no agent_id with non-empty meta); MainScreen accordingly lights up the "Working" state
  immediately and sets busy
- Polls history every 15s while busy (including queue reconciliation): a reply is visible within 15s of the
  in-flight turn completing, no longer depending on the unreceived turn.upsert; onWorkChanged(false) does a final
  alignment immediately; when polling finds the server no longer has active/queued (busy gone but the event
  missing), busy is cleared and a final alignment is done; polling is LaunchedEffect(sessionId), so the same
  session never stacks multiple timers
- Running is no longer labeled "queued": GET /prompts?status=queued's data.active is the currently executing
  prompt (no longer in queued[] since v0.37.2); Api.listQueuedPrompts returns PromptQueue(queued, active); during
  reconciliation an active text matching a local echo → labeled "Running" (same style as "Queued"); messages in
  active are never labeled "undelivered"; active entries without a local echo (submitted from another client) are
  rebuilt as "Running" bubbles

## 0.4.6 (2026-08-20)
- Fixed stale busy badges in the sidebar: the session list only refreshed when entering a session or when a turn
  ended, so other sessions' "Running" markers stayed in an old state; MainScreen adds a 30s timer that
  periodically calls loadSidebar (skipped while historyLoading or mid-load, does not interrupt the current UI);
  the sidebar is also refreshed once when WS onOpen reconnects successfully
- Fallback for queued messages dropped by the server (upstream MoonshotAI/kimi-code#3127: queued prompts under
  phantom busy are silently discarded): PendingEcho records its creation timestamp; during reconciliation an echo
  that is neither in history nor in the server queue and is older than 60s → marked "Undelivered (dropped by
  server)" with a red warning, no longer showing the fake "Queued" state; POST in flight (<60s) is kept as-is,
  and the queued state follows the server queue; queue-rebuilt bubbles (queued-$sessionId-hash) disappear
  directly when absent from both queue and history this round (if truly queued they will be rebuilt next round)

## 0.4.5 (2026-08-20)
- Fixed WS being disconnected and reconnected by the watchdog every ~40s (tens of thousands of times cumulative,
  each reconnect pulling the full history): observed on v0.37.2 that the server does not send heartbeat pings on
  non-loopback (Tailscale IP) connections, so the old passive liveness check of dropping after 35s without
  packets did not apply; changed to actively sending a protocol-level ping(0x9) after 15s idle, and only
  declaring the connection dead and reconnecting if still no frame after 35s (verified with the --wsprobe probe
  that the connection stays stable)

## 0.4.4 (2026-08-20)
- Fixed queued echoes being permanently wiped when switching sessions: pendingEchoes is now isolated by sessionId,
  and LaunchedEffect no longer clears it blindly
- The server queue is the source of truth: new GET /sessions/{id}/prompts?status=queued (Api.listQueuedPrompts);
  the queue is fetched and reconciled together with history refreshes (entering a session, turn end, WS
  reconnect) — echoes confirmed in history are removed; those in the queue are shown as "Queued" bubbles (local
  echoes and queue entries with the same text are deduplicated, shown only once); echoes in neither history nor
  queue (POST in flight) are kept; messages queued from other clients / before a restart can also be rebuilt as
  "Queued" bubbles
- Queued user bubbles carry a "Queued" small marker

## 0.4.3 (2026-08-20)
- Fixed queued echoes being wiped: sendPrompt returns status (running/queued); local echoes are registered as
  pendingEchoes, removed by text confirmation on history refresh (turn end, WS reconnect), and unconfirmed ones
  stay at the end of the list; echoes are removed on send failure
- When queued, the header status area shows "Queued…"
- Tool activity stream visible: frames with kind="tool" in WS frame.upsert are rendered as tool activity entries
  (🔧 name: summary, done marked ✓); the summary takes display.summary ?: inputText ?: input, with newlines
  stripped and truncated to 80 chars

## 0.4.2 (2026-08-15)
- Fixed lost sessions: listSessions no longer passes busy=false (running/approval-blocked sessions were filtered
  out)
- Session list busy=true shows a "Running" marker (small dot + label)

## 0.4 (2026-08-15)
- Session mode bar: plan/Swarm toggle, permission mode, model dropdown, goal mode (create/pause/resume/cancel)
- Mode state implemented per the official mechanism: persisted locally per session (config.properties); prompts
  carry mode fields at the top level
- Server-side finding: GET /profile does not return the real agent_config (v0.35.0 hardcodes an empty shell)
- Added --e2e-profile self-check entry

## 0.3.1 (2026-08-15)
- Fixed history messages in reverse order (the API returns newest first; must be reversed)
- Enter to send / Shift+Enter for newline

## 0.3 (2026-08-15)
- Business errors (HTTP 200 envelope with code!=0) shown as red error bubbles
- Phantom message filtering (system-injected user messages such as <system-reminder>)
- WS frame-level logging; --e2e full-pipeline self-check

## 0.2 (2026-08-14)
- Full-pipeline file logging at ~/.kimi-mobile/app.log
- Gate retry loop catches Throwable; startup auto-selects the most recent session and establishes WS

## 0.1 (2026-08-14)
- First release (Compose Multiplatform Desktop)
- Hand-written NIO transport layer (MiniHttp/WsClient) to work around the local OCLP JVM network stack defect
  (java.net/OkHttp connects but cannot read data)
- Tailscale gate, sessions/workspaces, WS streaming chat, multi-host profiles

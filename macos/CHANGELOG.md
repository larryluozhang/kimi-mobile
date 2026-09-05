# Changelog

## 0.7.2 (2026-09-05)
- Paginated history loading: GET /messages returns only the latest 100 raw messages (including tool roles), so after filtering very few visible bubbles may remain;
  a new "Load earlier messages" button at the top of the chat page pages backward with before_id=<current oldest message id> and prepends
  (reconcileHistory's time-based sorting is naturally compatible, pendingEchoes/queued/active reconcile unaffected;
  keyed LazyColumn prepending keeps the scroll position steady)
- Loading state hints: spinner + "Loading..." while loading, "No more" shown after reaching the top (this page's raw items count below page_size);
  full history refreshes on WS reconnect/busy end keep already-loaded older pages (AppState.olderHistory merged with dedup by id)
- Api.getMessages gains an optional beforeId parameter, returning MessagesPage(messages, hasMore)

## 0.7.1 (2026-09-05)
- Fix the right-click menu only offering Copy: SelectionContainer's built-in text menu was shadowing the fork item; switched to LocalTextContextMenu to provide a custom menu (Copy + Fork from here)
- Context usage display fix: format fixed as "usage/limit (percentage)", percentage always shown;
  limit taken from WS maxContextTokens (used only when >0), otherwise fall back to 1048576 (1M);
  decimal formatting pinned to Locale.US, fixing "690.k"-style formatting errors under some locales
- Fork from a specific message: user bubble right-click menu gains "Fork from here" → count the user messages n after this message
  (undelivered echoes not counted) → :fork full clone → :undo {"count":n} on the new session → switch to the new session;
  undo failure (e.g. 40911 nothing to undo) does not block the switch and is reported faithfully; /help updated accordingly
- Api.kt gains undoSession (POST /sessions/{id}:undo, body count field)

## 0.7.0 (2026-09-05)
- Context usage display: shown next to the session header title as "23.5k/1000k (2%)"; data source priority
  1) WS transcript.reset snapshot payload.snapshot.meta.agent's contextTokens/maxContextTokens (read at the same level as phase);
  2) transcript.ops meta.merge's agent.contextTokens (handled independently of phase, read even without phase);
  3) fallback to GET /sessions/{id}'s usage.context_tokens/context_limit (may be all 0 in practice; all-0 is treated as no data and not shown);
  reset on session switch, WS reports override the fallback value
- Topic fork button: the session header toolbar gains a "Fork" button, same path as the /fork command
  (Api.forkSession + switch to the new session on success; the /fork branch refactored to reuse the same doFork)
- /rename (or /title) command: POST /sessions/{id}/profile, body top-level title field;
  takes all remaining text after the first space as the new title, empty shows a usage hint, on success refreshes the sidebar/title; /help updated accordingly
- Api.kt gains renameSession / getSessionUsage

## 0.6.2 (2026-09-05)
- Fix / commands matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- The input box supports / slash commands (intercepted before sending, exact match case-insensitive; unrecognized text starting with / is still sent as a regular prompt):
  /compact → POST /sessions/{id}:compact to compact history (empty history returns server error 40910, reported as an error bubble);
  /archive → POST :archive, clears activeSessionId after archiving and refreshes the sidebar;
  /fork → POST :fork, switches to the returned new session on success;
  /abort (or /stop) → POST :abort to interrupt the current turn; /new → same flow as the sidebar "New session"; /help → pops up command documentation
- Api.kt gains compactSession / archiveSession / forkSession (fork returns the new session id)

## 0.5.2 (2026-09-02)
- Question card/dialog content area is height-limited and scrollable: with many questions and options the submit button at the bottom is always reachable (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Approval UI: poll GET /sessions/{id}/approvals?status=pending (every 5s); when the agent is stuck on approval, an approval card is shown (tool name/action/summary + approve/reject buttons),
  POST /approvals/{approval_id} submits the decision (approved/rejected); under manual permission the agent suspends when issuing a tool call that needs approval, and the server resumes after the local decision
- Question UI: poll GET /sessions/{id}/questions?status=pending (every 5s); single-choice RadioButton groups render questions[], with an "Other" + text input appended when allow_other,
  and a "Skip" button; answers is a mapping of question-id → answer object (kind=single with option_id / other with text / skipped);
  after POST /questions/{question_id} is submitted, the agent resumes and the turn continues
- Interrupt button: when busy, the chat header shows a "Stop" button, calling POST /sessions/{id}:abort (verified to return {"aborted":true}) to interrupt the current turn

## 0.4.9 (2026-08-21)
- Show the current version number at the bottom of the Settings window: build.gradle.kts's version is the single source of truth,
  written into the version.properties resource by the generateVersionProperties task at build time,
  and read from the classpath by AppVersion at runtime (shows "unknown" if reading fails)

## 0.4.8 (2026-08-20)
- Fix conversation order scrambling: echo/queued/executing bubbles were previously appended unconditionally at the end of the list; they now carry timestamps
  (ChatMessage.timeMillis; Api.QueuedPrompt carries created_at), and reconcile output is sorted by time (old→new)
- Badge-scrambling investigation: reconcileHistory gains RECONCILE diagnostic logs (reconcile inputs + the verdict for each echo);
  refreshHistory/session-entry loading gains cross-session race protection (results are discarded if the user switched sessions during the fetch)
- Idle sessions also get a fallback reconcile every 60s (still 15s when busy) — after the WS keepalive fix there are no more reconnect refreshes,
  so queued/executing/undelivered states of idle sessions can only stay fresh via periodic reconcile

## 0.4.7 (2026-08-20)
- Fix opening a running session showing a dead-silent UI (verified on v0.37.2: a WS client that subscribes mid-turn receives none of that turn's
  transcript.ops, not even the turn.upsert completion event):
  when handling transcript.reset, WsClient parses payload.snapshot.meta.agent.phase for the live phase
  (running/streaming/tool_call/ended…; among multiple resets from multiple agents only the main agent is taken —
  agent_id=="main" or no agent_id with meta non-empty); MainScreen uses this to immediately light up the "Working" state and set busy
- Poll history every 15s while busy (including queue reconcile): when an in-flight turn completes, the reply is visible within 15s,
  no longer relying on the unreceivable turn.upsert; onWorkChanged(false) immediately does a final alignment,
  when polling finds the server has no active/queued anymore (busy gone but the event missing) it sets busy off and does a final alignment;
  polling is LaunchedEffect(sessionId), so the same session never stacks multiple timers
- Executing is no longer labeled "queued": GET /prompts?status=queued's data.active is the currently executing prompt
  (since v0.37.2 it is not in queued[]), Api.listQueuedPrompts returns PromptQueue(queued, active);
  during reconcile, an active text matching a local echo → labeled "Executing" (styled like "Queued"), messages in active are never labeled "Undelivered";
  active entries with no local echo (submitted from other clients) are rebuilt as "Executing" bubbles

## 0.4.6 (2026-08-20)
- Fix stale sidebar busy badges: the session list previously refreshed only on session entry/turn end, so other sessions' "Running" labels could stay in an old state;
  MainScreen gains a 30s timer that periodically calls loadSidebar (skipped while historyLoading/mid-load, not interrupting the current UI),
  and the sidebar is also refreshed when WS onOpen reconnect succeeds
- Fallback for queued messages dropped by the server (upstream MoonshotAI/kimi-code#3127: under phantom busy, queued prompts are silently dropped):
  PendingEcho records its creation timestamp; during reconcile, an echo that is in neither history nor the server queue and has existed for over 60s → labeled "Undelivered (dropped by server)"
  with a red warning, no longer showing the fake "Queued" state; POSTs in flight (<60s) are kept as-is, and queued state follows the server queue;
  queue-rebuilt bubbles (queued-$sessionId-hash) disappear outright when present in neither this round's queue nor history (if truly queued, they will be rebuilt next round)

## 0.4.5 (2026-08-20)
- Fix the WS being disconnected and reconnected by the watchdog every ~40s (tens of thousands of times cumulatively, each reconnect pulling the full history):
  verified that the v0.37.2 server does not send heartbeat pings on non-loopback (Tailscale IP) connections,
  so the original passive liveness check of disconnecting after 35s without packets did not apply; now actively send a protocol-level ping(0x9) after 15s idle for keepalive,
  and only declare the connection dead and reconnect if still no frame arrives after 35s (verified with the --wsprobe probe that the connection stays stable)

## 0.4.4 (2026-08-20)
- Fix queued echoes being permanently wiped when switching sessions: pendingEchoes are now isolated by sessionId, and LaunchedEffect no longer blindly clears them
- The server queue is the source of truth: new GET /sessions/{id}/prompts?status=queued (Api.listQueuedPrompts),
  fetched together with history refreshes (session entry, turn end, WS reconnect) for reconcile —
  echoes confirmed in history are removed; those in the queue are shown as "Queued" bubbles (local echoes and queue entries with identical text are deduplicated, only one copy shown);
  echoes in neither history nor queue (POST in flight) are kept; messages queued from other clients or before a restart can also be rebuilt as "Queued" bubbles
- Queued user bubbles carry a small "Queued" label

## 0.4.3 (2026-08-20)
- Fix queued echoes being wiped: sendPrompt returns status (running/queued); local echoes are registered as pendingEchoes,
  confirmed by text and removed on history refresh (turn end, WS reconnect), unconfirmed ones kept at the end of the list; on send failure the echo is removed
- When queued, the header status area shows "Queued..."
- Tool activity visibility: frames with kind="tool" in WS frame.upsert are rendered as tool activity entries (🔧 name: summary, done marked ✓),
  summary taken from display.summary ?: inputText ?: input, newlines removed and truncated to 80 chars

## 0.4.2 (2026-08-15)
- Fix lost sessions: listSessions no longer passes busy=false (running/stuck-on-approval sessions were being filtered out)
- Session list shows a "Running" label for busy=true (small dot + text)

## 0.4 (2026-08-15)
- Session mode bar: Plan/Swarm toggle, permission mode, model dropdown, goal mode (create/pause/resume/cancel)
- Mode state implemented per the official mechanism: persisted locally per session (config.properties), prompts carry mode fields at the top level
- Server finding: GET /profile does not return the real agent_config (v0.35.0 hardcoded empty shell)
- New --e2e-profile self-check entry

## 0.3.1 (2026-08-15)
- Fix history messages in reverse order (the API returns newest first and must be reversed)
- Enter to send / Shift+Enter for newline

## 0.3 (2026-08-15)
- Business errors (HTTP 200 with code!=0) shown as red error bubbles
- Phantom message filtering (system-injected user messages such as <system-reminder>)
- WS frame-level logging; --e2e end-to-end self-check

## 0.2 (2026-08-14)
- End-to-end file logging at ~/.kimi-mobile/app.log
- Gate retry loop catches Throwable; on startup, automatically select the most recent session and establish the WS

## 0.1 (2026-08-14)
- First release (Compose Multiplatform Desktop)
- Handwritten NIO transport layer (MiniHttp/WsClient) to work around the JVM network stack defects of the local OCLP machine (java.net/OkHttp can connect but reads no data)
- Tailscale gate, sessions/workspaces, WS streaming chat, multi-host profiles

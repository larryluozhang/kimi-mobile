# Changelog

## 0.7.1 (2026-09-05)
- Fix right-click menu showing only Copy: SelectionContainer's built-in text menu shadowed the Fork option; use LocalTextContextMenu with a custom menu (Copy + Fork From Here)
- Context usage display fix: format pinned to "usage/limit (percent)", always including the percentage;
  the limit comes from WS maxContextTokens (used only when >0), otherwise falls back to 1048576 (1M);
  decimal formatting pinned to Locale.US, fixing "690.k"-style formatting errors seen under some locales
- Fork from a message: the user bubble right-click menu gains "Fork from here" → count n = user messages after this one
  (undelivered echoes are not counted) → :fork full clone → :undo {"count":n} on the new session → switch to the new session;
  undo failure (e.g. 40911 nothing to undo) does not block the switch and is reported truthfully; /help updated accordingly
- Api.kt gains undoSession (POST /sessions/{id}:undo, body count field)

## 0.7.0 (2026-09-05)
- Context usage display: shows "23.5k/1000k (2%)" next to the title in the session header; data source priority:
  1. contextTokens/maxContextTokens from the WS transcript.reset snapshot payload.snapshot.meta.agent (read alongside phase);
  2. agent.contextTokens from transcript.ops meta.merge (handled independently of phase; read even without phase);
  3. fallback GET /sessions/{id} usage.context_tokens/context_limit (observed to sometimes be all 0; all-0 is treated as no data and hidden);
  reset on session switch; WS reports override the fallback value
- Fork button: the session header toolbar gains a "Fork" button on the same path as the /fork command
  (Api.forkSession + switch to the new session on success; the /fork branch was refactored to reuse the same doFork)
- /rename (or /title) command: POST /sessions/{id}/profile with a top-level title field in the body;
  everything after the first space becomes the new title; empty input shows a usage hint; the sidebar/title refreshes on success; /help updated accordingly
- Api.kt gains renameSession / getSessionUsage

## 0.6.2 (2026-09-05)
- Fixed / commands to match on the first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Slash command support in the input field (intercepted before sending; exact case-insensitive match; unrecognized /-prefixed text is still sent as a regular prompt):
  /compact → POST /sessions/{id}:compact to compress history (empty history returns server error 40910, surfaced as an error bubble);
  /archive → POST :archive, then clears activeSessionId and refreshes the sidebar;
  /fork → POST :fork, switching to the returned new session on success;
  /abort (or /stop) → POST :abort to interrupt the current turn; /new → same flow as the sidebar "New session"; /help → command list popup
- Api.kt gains compactSession / archiveSession / forkSession (fork returns the new session id)

## 0.5.2 (2026-09-02)
- Question card/dialog content area is now height-capped and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isPhantomUserText now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Approval UI: polls GET /sessions/{id}/approvals?status=pending (every 5s); when the agent is stuck on approval, an approval card appears (tool name/action/summary + approve/reject buttons);
  POST /approvals/{approval_id} submits the decision (approved/rejected); when the agent issues an approval-requiring tool call under manual permission it suspends, and the server resumes once this client decides
- Question UI: polls GET /sessions/{id}/questions?status=pending (every 5s); renders questions[] as a single-choice RadioButton group, appending "Other" + a text field when allow_other is set,
  plus a "Skip" button; answers is a map of question id → answer object (kind=single with option_id / other with text / skipped);
  the agent resumes and the turn continues after POST /questions/{question_id}
- Interrupt button: a "Stop" button appears in the chat header while busy, calling POST /sessions/{id}:abort (observed to return {"aborted":true}) to interrupt the current turn

## 0.4.9 (2026-08-21)
- The Settings window footer now shows the current version: build.gradle.kts's version is the single source of truth,
  written at build time by the generateVersionProperties task into the version.properties resource,
  and read from the classpath at runtime by AppVersion (shows "unknown" if the read fails)

## 0.4.8 (2026-08-20)
- Fixed out-of-order conversations: echo/queued/executing bubbles used to be appended unconditionally to the end of the list; they now carry timestamps
  (ChatMessage.timeMillis; Api.QueuedPrompt carries created_at), and the reconcile output is sorted by time (old → new)
- Badge-mismatch diagnostics: reconcileHistory gains RECONCILE diagnostic logs (reconcile inputs + the verdict for each echo);
  refreshHistory/session-entry loading gains cross-session race protection (results are discarded if the user switched sessions during the fetch)
- Idle sessions are also reconciled every 60s as a fallback (still 15s while busy) — after the WS liveness fix there are no more reconnect-triggered refreshes,
  so the queued/executing/undelivered states of idle sessions can only stay fresh via periodic reconciliation

## 0.4.7 (2026-08-20)
- Fixed the dead-silent UI when opening a session that is already running (observed on v0.37.2: a WS client that subscribes mid-turn receives none of that turn's
  transcript.ops, not even the turn.upsert completion event):
  WsClient parses payload.snapshot.meta.agent.phase for the real-time phase when handling transcript.reset
  (running/streaming/tool_call/ended...; among multiple resets from multiple agents, only the main agent is used —
  agent_id=="main" or no agent_id with non-empty meta), and MainScreen immediately lights up the "working" state and sets busy accordingly
- Poll history every 15s while busy (including queue reconciliation): the reply becomes visible within 15s of the in-flight turn completing,
  no longer relying on the undeliverable turn.upsert; onWorkChanged(false) triggers one final alignment immediately,
  and when polling finds the server has no active/queued left (busy cleared but the event was missed), it sets busy off and does one final alignment;
  polling is LaunchedEffect(sessionId), so timers never stack within the same session
- "Executing" is no longer called "queued": GET /prompts?status=queued's data.active is the currently executing prompt
  (no longer inside queued[] since v0.37.2); Api.listQueuedPrompts returns PromptQueue(queued, active);
  during reconciliation an active text matching a local echo → marked "executing" (styled like "queued"); messages in active are never marked "undelivered";
  active entries without a local echo (submitted from another client) are rebuilt as "executing" bubbles

## 0.4.6 (2026-08-20)
- Fixed stale busy badges in the sidebar: the session list only refreshed on session entry/turn end, so other sessions' "running" markers stayed in an old state;
  MainScreen adds a 30s timer that periodically calls loadSidebar (skipped while historyLoading/mid-load, without interrupting the current UI),
  and the sidebar also refreshes once on WS onOpen reconnect
- Fallback for queued messages dropped by the server (upstream MoonshotAI/kimi-code#3127: under phantom-busy, queued prompts are silently dropped):
  PendingEcho records its creation timestamp; during reconciliation, an echo in neither history nor the server queue that has existed for over 60s → marked "undelivered (dropped by server)"
  as a red warning, no longer showing the fake "queued" state; POSTs still in flight (<60s) are kept as-is; the queued state follows the server queue;
  queue-rebuilt bubbles (queued-$sessionId-hash) disappear directly when in neither queue nor history this round (if truly queued they will be rebuilt next round)

## 0.4.5 (2026-08-20)
- Fixed the watchdog disconnecting and reconnecting the WS every ~40s (tens of thousands of times cumulatively, each reconnect pulling full history):
  observed on v0.37.2 that the server does not send heartbeat pings on non-loopback (Tailscale IP) connections,
  so the original passive liveness check of "disconnect after 35s of silence" does not apply; now a protocol-level ping (0x9) is sent proactively after 15s of idleness,
  and only 35s with no frames at all is judged as a dropout triggering reconnect (verified stable with the --wsprobe probe)

## 0.4.4 (2026-08-20)
- Fixed queued echoes being permanently wiped on session switch: pendingEchoes are now isolated per sessionId; LaunchedEffect no longer blindly clears them
- The server queue is now the source of truth: added GET /sessions/{id}/prompts?status=queued (Api.listQueuedPrompts);
  history refreshes (session entry, turn end, WS reconnect) also fetch the queue for reconciliation —
  echoes confirmed in history are removed; ones in the queue show as "queued" bubbles (local echoes and queue entries with identical text are deduplicated to a single copy);
  echoes in neither history nor queue (POST in flight) are kept; messages queued from other clients or before a restart can also be rebuilt as "queued" bubbles
- Queued user bubbles carry a small "queued" marker

## 0.4.3 (2026-08-20)
- Fixed queued echoes being wiped: sendPrompt returns status (running/queued); local echoes are registered as pendingEchoes,
  confirmed by text and removed on history refresh (turn end, WS reconnect), with unconfirmed ones kept at the end of the list; echoes are removed on send failure
- The header status area shows "Queued..." when queued
- Tool activity is now visible: WS frame.upsert frames with kind="tool" render as tool activity entries (🔧 name: summary, ✓ when done),
  with the summary taken from display.summary ?: inputText ?: input, newlines stripped and truncated to 80 chars

## 0.4.2 (2026-08-15)
- Fixed sessions disappearing: listSessions no longer passes busy=false (running/approval-stuck sessions were being filtered out)
- Sessions with busy=true in the session list show a "running" marker (dot + label)

## 0.4 (2026-08-15)
- Session mode bar: plan/Swarm toggle, permission mode, model dropdown, goal mode (create/pause/resume/cancel)
- Mode state implemented per the official mechanism: persisted locally per session (config.properties); mode fields ride along at the top level of prompts
- Server finding: GET /profile does not return the real agent_config (v0.35.0 hardcodes an empty shell)
- Added the --e2e-profile self-check entry

## 0.3.1 (2026-08-15)
- Fixed history messages in reverse order (the API returns newest first and must be reversed)
- Enter to send / Shift+Enter for newline

## 0.3 (2026-08-15)
- Business errors (HTTP 200 wrapping code!=0) shown as red error bubbles
- Phantom message filtering (system-injected user messages such as <system-reminder>)
- WS frame-level logging; --e2e end-to-end self-check

## 0.2 (2026-08-14)
- Full-pipeline file logging at ~/.kimi-mobile/app.log
- Gate retry loop catches Throwable; on startup the most recent session is auto-selected and a WS connection is established

## 0.1 (2026-08-14)
- First release (Compose Multiplatform Desktop)
- Hand-written NIO transport layer (MiniHttp/WsClient) working around this machine's OCLP JVM network stack defect (java.net/OkHttp connect but read no data)
- Tailscale gating, sessions/workspaces, WS streaming chat, multi-host profiles

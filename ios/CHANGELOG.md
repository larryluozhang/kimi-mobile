# Changelog

## 0.7.2 (2026-09-05)
- Added paginated loading of history messages: a "Load earlier messages" button at the top of the chat page pulls the previous page by before_id and prepends it (GET /messages?page_size=100 only returns the most recent 100 raw messages including tool ones, so after filtering only a few visible bubbles may remain; before_id verified to work in practice); APIClient.getMessages supports an optional beforeId and returns MessagesPage (filtered messages + the id/timestamp of the oldest raw message in this page as cursor + hasMore as a rough guess = whether the number of raw items in this page reaches page_size); the cursor takes the minimum of the oldest raw messages across pages (monotonic toward older; the newest page brought back by polling does not roll the cursor back); earlierHistory is deduplicated by id against the newest page before merged rendering (applyHistory sorts by createdAt so prepending happens naturally, pendingLocal/queued/active composite bubbles are unaffected; echo/queue text matching still only applies to the newest page to avoid old messages with identical text being misjudged as "confirmed"); loading state indicators (loading... / no more messages, a page whose raw item count < page_size is treated as having reached the beginning); after prepending, the scroll position is anchored to the original first message instead of jumping to the bottom

## 0.7.1 (2026-09-05)
- Fixed context usage display: limit is taken from WS maxContextTokens and used only when >0, otherwise falls back to 1048576 (no longer hides the entire entry when limit is missing/0); format is fixed as "usage/limit (percentage)", always including the percentage in all cases; fixed a decimal formatting bug (formatTokens now formats with %.1f first and then trims the ".0" suffix, so rounding up to an integer no longer leaks decimals like "1000.0k")
- Added "Fork from here": long-press a user bubble to open the contextMenu entry; counts the number n of user messages after this one that are already in the server-side history (excluding local optimistic echoes pendingLocal and queued-N/active composite bubbles to avoid over-undoing) → :fork fully clones the current session → :undo {"count":n} on the new session (verified in practice) → switches to the new session; APIClient.sessionAction supports an optional body, added undoSession

## 0.7.0 (2026-09-05)
- Added context usage display (status area on the chat page, e.g. "Context 23.5k/1000k (2%)"): data source priority 1) contextTokens/maxContextTokens of payload.snapshot.meta.agent from the WS transcript.reset snapshot → 2) agent.contextTokens/maxContextTokens from transcript.ops meta.merge (missing fields keep previous values) → 3) fallback to usage.context_tokens/context_limit from GET /sessions/{id} (may be all zeros in practice, in which case nothing is shown); WSService added a .contextUsage event, APIClient added getSessionUsage
- Added a "Fork" button to the chat page toolbar: same path as /fork (POST :fork, on success switches to the new session via forkTarget); the /fork handling logic was extracted into the public ChatViewModel.fork() for reuse
- Added /rename (or /title) command: POST /sessions/{id}/profile with top-level body {"title":"..."} (verified in practice); takes all remaining text after the first space as the new title, shows usage hint when the argument is empty; on success refreshes the navigation bar title (sessionTitle changed to @Published) and posts a kimiSessionRenamed notification so MainView silently refreshes the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / command matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (aligned with Android): the model is no longer bundled (project.yml removed the Resources/models reference, the directory keeps only .gitkeep); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; the settings page added an "Offline speech model" section: model status, editable download URL (UserDefaults voice_model_url, defaulting to GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download offline model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation extracts to Application Support, added ZIPFoundation SwiftPM dependency); the voice button in offline-only mode prompts "Please download the offline model from the settings page first" when the model is not downloaded
- Added / command support (intercepted before sending, aligned with macOS/Android): /compact→POST :compact, /archive→:archive (returns to the list on success), /fork→:fork (parses data.id/session_id to switch to the new session), /abort (/stop)→:abort, /new→returns to the list and creates a new session (notifies MainView), /help→command list popup; other inputs starting with / are sent as normal prompts; APIClient added a generic method sessionAction (POST /sessions/{id}:{action}, abortSession refactored to reuse it)

## 0.6.0 (2026-09-05)
- Added self-hosted offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + streaming-zipformer Chinese-English bilingual streaming model (int8, about 189MB, placed in KimiMobile/Resources/models/zipformer-bilingual/, bundled via folder reference, the directory is in .gitignore and not committed to git); added SpeechOnnx (16kHz mono resampling + streaming feeding + endpoint detection segmentation, interface aligned with SpeechInput)
- The settings page "Preferences" added a speech recognition engine selector (auto prefer offline / offline only / system only, stored in UserDefaults voice_engine); in auto mode the voice button prefers offline recognition when the model exists, and automatically falls back to SFSpeechRecognizer on load failure (the old SpeechInput code is kept)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area is height-limited and scrollable: the submit button at the bottom is always reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected added the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected added the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected added the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added approval UI: polls GET /sessions/{id}/approvals?status=pending (data.items[] contains approval_id/tool_name/action/tool_input_display.summary); the approval card shows tool name, action and summary; approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); cards are optimistically removed on submit, and polling rolls back on failure
- Added Q&A support: polls GET /sessions/{id}/questions?status=pending (data.items[] contains question_id, questions[] (each with id/question/header/options/allow_other)); the Q&A card offers single choice per question (kind=single/option_id), and when allow_other=true an "other" text box is provided (kind=other/text); submission POSTs /sessions/{id}/questions/{question_id} (body {"answers":{"<question_id>":{...}}}), plus a "skip" button (all questions kind=skipped)
- Added an interrupt button: when busy, the input bar shows a red stop button that calls POST /sessions/{id}:abort (colon-suffix syntax, server verified to return {"aborted":true}); the send button remains usable (queuing is still possible while busy)
- Approvals/Q&A are polled on the same cycle as history reconciliation (busy 15s / idle 60s, same cadence as startBusyPolling), with an immediate first fetch on onAppear; fetch failures do not clear existing cards to avoid flicker

## 0.4.9 (2026-08-21)
- Added version display at the bottom of the settings page "Preferences" ("Version 0.4.9 (2)"), taken from Bundle.main's CFBundleShortVersionString / CFBundleVersion, automatically generated from project.yml's MARKETING_VERSION / CURRENT_PROJECT_VERSION

## 0.4.8 (2026-08-20)
- Fixed "conversation order scrambled": applyHistory reconciliation used to unconditionally append local echo/queued/executing bubbles to the end of the list, so ordering broke when they predated the latest history entries; ChatMessage already has createdAt, APIClient.listQueuedPrompts now returns a QueuedPrompt structure with created_at (for both active/queued), and after reconciliation the full list is sorted by createdAt ascending (entries without timestamps go last)
- Fixed "idle session badge state frozen": busy polling previously only ran while busy; busyPollTask now polls every 15s when busy and every 60s when idle as a fallback reconciliation (started onAppear, stopped when the view disappears, idempotent against stacking), and no longer stops polling when turning idle
- Added [Reconcile] prefixed diagnostic logs at the applyHistory reconciliation entry: history count, queue/active summary, and the verdict for each local echo (confirmed in history / executing / queued / undelivered / POST in flight)
- Synced fixes with the macOS client 0.4.8

## 0.4.7 (2026-08-20)
- Fixed "no streaming content/status when entering a session mid-turn" (verified against v0.37.2 server: late subscribers do not receive the turn's transcript.ops):
  - When handling transcript.reset, WSService parses payload.snapshot.meta.agent.phase and re-emits a .phase event (with multiple agents' resets only main is used: agent_id=="main" or meta non-empty); ChatViewModel immediately shows "working/thinking" accordingly (phase kind added tool_call recognition)
  - Polls history every 15s while busy (including queue reconciliation), with a final refresh when busy ends; stops automatically when the view disappears or turns idle, idempotent against stacking; when work_changed may also not be received, the phase event serves as fallback trigger (running/tool_call/streaming start, ended/interrupted stop); loadHistory's "don't overwrite while streaming" guard relaxed to "don't overwrite only when there are streaming frames", so late subscribers without frames reconcile normally
- Fixed "executing messages shown as queued": data.active from GET /prompts?status=queued is the currently executing prompt (not in queued[]), APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconciliation, local echoes with the same text as active are marked "executing" (ChatMessage added isExecuting, ChatView shows a small label below the bubble), no longer marked "queued"/"undelivered"; for active entries without a local echo (re-entering a session/restarting the app) an "executing" bubble is rendered as a supplement

## 0.4.6 (2026-08-20)
- Fixed "stale busy badges in the session list": the list only updated on entry/manual refresh, so the "running" spinner would linger in an old state; MainView changed to a .task loop that silently refreshes the session list every 30s (only updates sessions, without touching loading/error banners), automatically stopping with .task cancellation when the view disappears
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped, appearing neither in the queue nor in history): during applyHistory reconciliation, pendingLocal entries that are neither in history nor in the queue and are over 60s old → marked "undelivered (dropped by server)" (ChatMessage added deliveryFailed), no longer shown as "queued"; POST in flight (<60s) is kept normally
- ChatView user bubbles added a red small-text warning "undelivered (dropped by server)"; queued-N bubbles for server-side queue reconstruction are rebuilt with every reconciliation anyway, and disappear naturally when neither queue nor history contains them

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappearing after re-entering a session/killing the app": v0.4.3's pendingLocal was memory-only, so queued messages remained in the server-side queue but were no longer shown in the UI; changed to treat the server-side queue as the source of truth — added GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts), fetching the queue alongside history for reconciliation: echoes confirmed in history are removed, echoes in the queue are marked "queued", queued entries without a local echo are additionally rendered as queued bubbles (deduplicated by text); when sending returns queued, the local echo is immediately marked "queued"
- ChatView user bubbles added a "queued" marker (small text + spinner below the bubble)

## 0.4.3 (2026-08-20)
- Fixed "queued messages being wiped": when busy, POST /prompts returns status="queued", and queued user messages do not enter the GET /messages history until their turn to execute; after sending, the local optimistic echo enters pendingLocal pending confirmation, and is removed only when a user message with identical text appears server-side on history refresh, otherwise kept at the end of the list; the queued status bar hints "queuing..."; on send failure the echo is removed (APIClient.sendPrompt returns status)
- Added tool activity visibility: frames with frame.kind=="tool" in WS transcript.ops show tool activity entries in the message stream ("🔧 Bash: date", spinner for running / ✓ for done, summary taken from display.summary ?: inputText ?: input description, newlines stripped and truncated to 80 characters); temporary entries disappear naturally when history refreshes at turn end
- Synced fixes with Android 0.4.3

## 0.4.2 (2026-08-15)
- Fixed "session loss": GET /sessions no longer carries busy=false, so sessions that are running or stuck on approval appear in the list again (list rows already have busy spinner markers)
- Synced fixes with the Android / macOS clients

## 0.4 (2026-08-15) — First release
- Native SwiftUI client, feature-aligned with Android 0.4.1 / macOS 0.4
- Tailscale gating (healthz probing + onboarding page + automatic retry)
- Session list + workspace switching (default mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential-backoff reconnection)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (preset 146 work machine / Mac laptop, tokens stored in Keychain)
- Session mode bar: plan/Swarm/permission/model/goal; mode states persisted locally per session (UserDefaults) + prompts carry mode fields (official mechanism)
- Phantom message filtering (<system-reminder> etc.); HTTP 200 wrapped error parsing and display to the user

# Changelog

## 0.7.2 (2026-09-05)
- New paginated history loading: a "Load earlier messages" button at the top of the chat page fetches the previous page by before_id and prepends it (GET /messages?page_size=100 returns only the latest 100 raw messages including tool roles, so after filtering only a few visible bubbles may remain; before_id verified working); APIClient.getMessages supports an optional beforeId and returns MessagesPage (filtered messages + the oldest raw message id/time of this page as cursor + hasMore roughly determined by whether this page's raw items count reaches page_size); the cursor takes the minimum of each page's oldest raw message (monotonically moving older, the latest page brought back by polling does not regress the cursor); earlierHistory and the latest page are deduplicated by id and merged for rendering (applyHistory sorts by createdAt so entries are naturally prepended; pendingLocal/queued/active synthesized bubbles are unaffected; echo/queue text matching still only applies to the latest page, avoiding old messages with identical text being misjudged as "confirmed"); loading state hints (Loading.../No more, a page whose raw items count < page_size is treated as the end); after prepending, scrolling stays anchored to the original first message instead of jumping to the bottom

## 0.7.1 (2026-09-05)
- Fix context usage display: limit taken from WS maxContextTokens, used only when >0, otherwise fall back to 1048576 (the whole row is no longer hidden when limit is missing/0); format fixed as "usage/limit (percentage)", percentage always shown; fix decimal formatting bug (formatTokens now formats with %.1f first and then trims the ".0" suffix, so values rounded to an integer no longer leak decimals like "1000.0k")
- New "Fork from here": long-press contextMenu entry on a user bubble; count the user messages n already in server history after this message (excluding local optimistic echo pendingLocal and queued-N/active synthesized bubbles, to avoid over-undoing) → :fork full clone of the current session → :undo {"count":n} on the new session (verified working) → switch to the new session; APIClient.sessionAction supports an optional body; new undoSession

## 0.7.0 (2026-09-05)
- New context usage display (chat page status area, e.g. "Context 23.5k/1000k (2%)"): data source priority 1) WS transcript.reset snapshot payload.snapshot.meta.agent's contextTokens/maxContextTokens → 2) transcript.ops meta.merge's agent.contextTokens/maxContextTokens (missing fields keep old values) → 3) fallback to GET /sessions/{id}'s usage.context_tokens/context_limit (may be all 0 in practice, in which case nothing is shown); WSService gains a .contextUsage event, APIClient gains getSessionUsage
- New "Fork" button in the chat page toolbar: same path as /fork (POST :fork, on success switch to the new session via forkTarget); the /fork handling logic is extracted into the public ChatViewModel.fork() for reuse
- New /rename (or /title) command: POST /sessions/{id}/profile, body top-level {"title":"..."} (verified); takes all remaining text after the first space as the new title, empty argument shows a usage hint; on success the navigation bar title is refreshed (sessionTitle changed to @Published) and a kimiSessionRenamed notification is posted so MainView silently refreshes the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fix / commands matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (aligned with Android): the model is no longer bundled (project.yml no longer references Resources/models; the directory keeps only .gitkeep); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; the Settings page gains an "Offline speech model" section: model status, editable download URL (UserDefaults voice_model_url, default GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download offline model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation extracts to Application Support; new ZIPFoundation SwiftPM dependency); the voice button in offline-only mode with the model not downloaded prompts "Please download the offline model in Settings first"
- New / command support (intercepted before sending, aligned with macOS/Android): /compact→POST :compact, /archive→:archive (returns to the list on success), /fork→:fork (parses data.id/session_id and switches to the new session), /abort (/stop)→:abort, /new→returns to the list and creates a new session (notifies MainView), /help→command list dialog; other input starting with / is sent as a regular prompt; APIClient gains the generic method sessionAction (POST /sessions/{id}:{action}, abortSession now reuses it)

## 0.6.0 (2026-09-05)
- New bundled offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + streaming-zipformer Chinese-English bilingual streaming model (int8, ~189MB, placed in KimiMobile/Resources/models/zipformer-bilingual/, bundled as a folder reference; the directory is added to .gitignore and not committed to git); new SpeechOnnx (16kHz mono resampling + streaming feed + endpoint detection segmentation, interface aligned with SpeechInput)
- The Settings page "Preferences" gains a speech recognition engine selection (auto prefer offline / offline only / system only, stored in UserDefaults voice_engine); the voice button in auto mode prefers offline recognition when the model exists, and automatically falls back to SFSpeechRecognizer on load failure (old SpeechInput code retained)

## 0.5.2 (2026-09-02)
- Question card/dialog content area is height-limited and scrollable: with many questions and options the submit button at the bottom is always reachable (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fix background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- New approval UI: poll GET /sessions/{id}/approvals?status=pending (data.items[] contains approval_id/tool_name/action/tool_input_display.summary); the approval card shows tool name · action + summary, and the approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); the card is optimistically removed on submit, and polling rolls it back on failure
- New question support: poll GET /sessions/{id}/questions?status=pending (data.items[] contains question_id, questions[] (each with id/question/header/options/allow_other)); the question card is single-choice per question (kind=single/option_id), and when allow_other=true an "Other" text box is provided (kind=other/text); submit POST /sessions/{id}/questions/{question_id} (body {"answers":{"<question-id>":{...}}}), plus a "Skip" button (all questions kind=skipped)
- New interrupt button: when busy, the input bar shows a red stop button; tapping it calls POST /sessions/{id}:abort (colon-suffix syntax, server verified to return {"aborted":true}); the send button stays usable (queueing still works while busy)
- Approvals/questions are polled in the same cycle as history reconcile (busy 15s / idle 60s, startBusyPolling same cadence), with an immediate first fetch onAppear; a failed fetch does not clear existing cards, avoiding flicker

## 0.4.9 (2026-08-21)
- New version number display at the bottom of the Settings page "Preferences" ("Version 0.4.9 (2)"), taken from Bundle.main's CFBundleShortVersionString / CFBundleVersion, automatically generated from project.yml's MARKETING_VERSION / CURRENT_PROJECT_VERSION

## 0.4.8 (2026-08-20)
- Fix "conversation order scrambling": applyHistory reconcile previously piled local echo/queued/executing bubbles unconditionally at the end of the list, scrambling the order when they were earlier than the latest history entry; ChatMessage already has createdAt, APIClient.listQueuedPrompts now returns a QueuedPrompt structure with created_at (both active/queued included), and after reconcile the whole list is sorted ascending by createdAt (entries without a timestamp go last)
- Fix "idle session badge state freezing": busy polling previously only ran while busy; busyPollTask now runs every 15s when busy and every 60s when idle as a fallback reconcile (started onAppear, stopped when the view disappears, idempotent against stacking); transitioning to idle no longer stops polling
- applyHistory reconcile entry gains [Reconcile]-prefixed diagnostic logs: history count, queue/active summary, and the verdict for each local echo (history-confirmed/executing/queued/undelivered/POST-in-flight)
- Synced fix with macOS 0.4.8

## 0.4.7 (2026-08-20)
- Fix "no streaming content/status when entering a session mid-turn" (v0.37.2 server verified: late subscribers receive no transcript.ops for that turn):
  - When handling transcript.reset, WSService parses payload.snapshot.meta.agent.phase and re-emits a .phase event (among multiple resets from multiple agents, only main is taken: agent_id=="main" or meta non-empty); ChatViewModel uses this to immediately show "Working/Thinking" (phase kind gains tool_call recognition)
  - Poll history every 15s while busy (including queue reconcile), with a final refresh when busy disappears; automatically stopped when the view disappears/turns idle, idempotent against stacking; when work_changed may also be unreceivable, phase events (running/tool_call/streaming start, ended/interrupted stop) trigger the fallback; loadHistory's "don't overwrite while streaming" guard is relaxed to "don't overwrite only when there are streaming frames", so late subscribers with no frames reconcile as usual
- Fix "executing messages shown as queued": GET /prompts?status=queued's data.active is the currently executing prompt (not in queued[]), APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconcile, a local echo matching active's text is labeled "Executing" (ChatMessage gains isExecuting, ChatView shows a small label under the bubble), no longer labeled "Queued"/"Undelivered"; active entries with no local echo (re-entering a session/restarting the app) get an "Executing" bubble rendered

## 0.4.6 (2026-08-20)
- Fix "stale busy badge in the session list": the list previously updated only on entry/manual refresh, so the "Running" spinner could stay in an old state; MainView now uses a .task loop to silently refresh the session list every 30s (only sessions updated, loading/error bar untouched), automatically stopping when the view disappears as the .task is cancelled
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped, appearing in neither the queue nor history): during applyHistory reconcile, a pendingLocal entry that is in neither history nor queue and has existed for over 60s → labeled "Undelivered (dropped by server)" (ChatMessage gains deliveryFailed), no longer shown as "Queued"; POSTs in flight (<60s) are kept as-is
- ChatView user bubbles gain a red small-text "Undelivered (dropped by server)" warning; queued-N bubbles rebuilt from the server queue are already rebuilt on every reconcile and naturally disappear when present in neither queue nor history

## 0.4.4 (2026-08-20)
- Fix "queued messages disappearing after re-entering a session / killing the app": v0.4.3's pendingLocal lived only in memory, so queued messages, although in the server queue, were no longer shown in the UI; now the server queue is the source of truth — new GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts), fetched together with history for reconcile: echoes confirmed in history are removed, echoes in the queue are labeled "Queued", queue entries with no local echo are rendered as queued bubbles (deduplicated by text); when sending returns queued, the local echo is immediately labeled "Queued"
- ChatView user bubbles gain a "Queued" label (small text under the bubble + spinner)

## 0.4.3 (2026-08-20)
- Fix "queued messages being wiped": when busy, POST /prompts returns status="queued", and a queued user message does not enter the GET /messages history until its turn to execute; the local optimistic echo after sending enters pendingLocal awaiting confirmation, and is removed only when a user message with the same text appears server-side on history refresh, otherwise kept at the end of the list; the queued status bar shows "Queued..."; on send failure the echo is removed (APIClient.sendPrompt returns status)
- New tool activity visibility: frames with frame.kind=="tool" in WS transcript.ops are shown in the message stream as tool activity entries ("🔧 Bash: date", running spinner / done ✓, summary taken from display.summary ?: inputText ?: input description, newlines removed and truncated to 80 chars); temporary entries naturally disappear when history refreshes at turn end
- Synced fix with Android 0.4.3

## 0.4.2 (2026-08-15)
- Fix "lost sessions": GET /sessions no longer passes busy=false, so running/pending-approval sessions are shown in the list again (list rows already have a busy spinner label)
- Synced fix with Android / macOS

## 0.4 (2026-08-15) — first release
- Native SwiftUI client, feature parity with Android 0.4.1 / macOS 0.4
- Tailscale gate (healthz probe + onboarding page + auto retry)
- Session list + workspace switching (default mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential backoff reconnect)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (preset 146 workstation / Mac laptop, token stored in Keychain)
- Session mode bar: Plan/Swarm/Permission/Model/Goal; mode state persisted locally per session (UserDefaults) + prompts carry mode fields (official mechanism)
- Phantom message filtering (<system-reminder> etc.); 200-wrapped error parsing and display to the user

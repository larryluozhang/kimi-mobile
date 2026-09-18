# Changelog

## 0.7.4 (2026-09-18)
- In-app update reminder (reminder only; Apple prohibits self-installation outside the App Store): AppUpdateChecker fetches the `ios` node of `latest.json` from the `app-latest` channel and compares build numbers; a 24h-throttled automatic check runs on launch (`ProfileStore.lastUpdateCheck`) plus a "Check for Updates" button on the version row in Settings; when a new version is found, a sheet shows the version and release notes with "iOS cannot install updates automatically; contact your administrator or update via Xcode"
- Plain-language permission mode names: Confirm Every Step / Standard Auto / Full Autonomy (Dangerous) (values unchanged; unknown values fall back to their raw form); every time `auto` is picked, a confirmationDialog ⚠️ warning appears (cancelling automatically reverts); on `loadProfile`, the `server` field of meta is fetched to identify the backend, and the yolo description switches by backend
- Approval cards display the full plan (plan_review): ApprovalItem extended with displayKind/plan/options + isPlanReview; tapping "View Plan" on an ExitPlanMode approval opens PlanReviewSheet (full-screen sheet: MarkdownContent renders the full plan + single-choice options + rejection-note TextField + approve/reject); the response body supports feedback/selected_label
- Fixed a potential race where opening a session did not scroll to the bottom (same root cause as Android): scroll unconditionally to the bottom when the first content arrives (hasInitiallyScrolled, deferred one runloop to avoid ScrollViewProxy's scrollTo silently failing on not-yet-laid-out rows), then resume "follow only when at the bottom"
- "/" command completion menu: a filtered panel pops up above the input field (8 commands with Chinese descriptions); tapping one fills in "/cmd "
- Partial message copying: the bubble contextMenu gains "Select Text", opening a sheet with Text.textSelection(.enabled) to select and copy the original text
- Per-session input drafts: new SessionDraftStore (UserDefaults session_drafts_v1; empty drafts delete the key); restored on init, saved on onChange(of: input), cleared naturally after sending
- Removed the bundled sherpa-onnx offline speech engine (slimmer app size; voice input keeps the system SFSpeechRecognizer path)

## 0.7.3 (2026-09-10)
- Removed the bundled sherpa-onnx offline speech engine (slimmer app size); voice input keeps the system recognition path (iOS/macOS only; the Android engine was switched to on-demand download)
- Image attachments in chat: new photo button (PhotosPicker) in the input bar uploads the picked image immediately (`APIClient.uploadFile`, POST /api/v1/files, hand-written multipart body with field `file` → data.id) and keeps a small preview with a remove button until sent; sending now supports image+text mixed content — `APIClient.sendPrompt` takes an optional `imageFileId` and prepends a `{"type":"image","source":{"kind":"file","file_id":...}}` block to the content array (image-only messages with empty text are allowed; the send button stays enabled while a pending image exists). Picked photos are normalized to JPEG (compression 0.85) so HEIC shots stay compatible with the server and the local preview
- Message bubbles render image blocks: `APIClient.getMessages` now extracts `file_id` from `image` content blocks into the new `ChatMessage.imageFileId` (pure-image messages are no longer filtered out), and `MessageBubble` shows them via the new `AuthedImage` view (RemoteImage.swift) — since AsyncImage cannot send headers, images are fetched with `APIClient.fetchFile` (GET /api/v1/files/{file_id} with Authorization Bearer) returning raw Data → UIImage, backed by a simple in-memory NSCache (`ImageCache`); local optimistic echoes, history reconciliation matching (text + imageFileId), and queued/executing badges all work with image messages
- Model picker now uses a dynamic server-side list: new `APIClient.listModels()` (GET /api/v1/models → data.items[] with provider/model/display_name/max_context_size); ChatViewModel fetches on session entry (`loadModels()`), ModeBarView renders the dynamic list (display_name preferred, annotated with context size) and falls back to the built-in `Constants.availableModels` presets when the fetch fails or is empty; the currently selected model is appended if missing from the list so the Picker selection never dangles. SettingsView's global model row switches from free-text input to the same dynamic list (refetched when the active host profile changes), falling back to the TextField when unavailable
- Context limit now follows the current model: `ChatViewModel.effectiveContextLimit` resolves the session model's `max_context_size` from the server model list, with priority current-model max_context_size > WS maxContextTokens > fallback 1048576; `contextUsageText` uses it, so the limit updates automatically after a model switch
- Auto-scroll no longer yanks reading position: ChatView now tracks whether the last message bubble is visible in the LazyVStack (`isAtBottom`) and only scrolls to bottom on refresh/streaming updates when the user is already at the bottom; pagination prepend anchor scrolling (scrollAnchorAfterPrepend) is unchanged and takes precedence; sending your own message is treated as an explicit return-to-bottom intent

## 0.7.2 (2026-09-05)
- Added paginated loading of history messages: a "Load earlier messages" button at the top of the chat page fetches the previous page by before_id and prepends it (GET /messages?page_size=100 returns only the latest 100 raw messages including tool entries, so after filtering only a few visible bubbles may remain; before_id verified working); APIClient.getMessages supports an optional beforeId and returns MessagesPage (filtered messages + the oldest raw message id/timestamp of the page as cursor + a rough hasMore = whether the page's raw item count reaches page_size); the cursor takes the minimum of the oldest raw message across pages (monotonically older; the latest page brought back by polling never regresses the cursor); earlierHistory is deduplicated by id against the latest page and merged for rendering (applyHistory sorts by createdAt so prepending happens naturally; pendingLocal/queued/active synthesized bubbles are unaffected; echo/queue text matching still only targets the latest page to avoid misjudging old messages with identical text as "confirmed"); loading-state hints ("Loading…" / "No more"; a page whose raw item count < page_size is treated as the end); after prepending, scrolling anchors to the original first message instead of jumping to the bottom

## 0.7.1 (2026-09-05)
- Fixed context usage display: limit is taken from WS maxContextTokens and used only when >0, otherwise falls back to 1048576 (no longer hides the whole entry when limit is missing or zero); format fixed to "usage/limit (percentage)" with a percentage in all cases; fixed a decimal formatting bug (formatTokens now formats with %.1f first and then strips the ".0" suffix, so rounding to an integer no longer leaks decimals like "1000.0k")
- Added "Fork from here": a long-press contextMenu entry on user bubbles counts the number n of user messages after that message already in server history (excluding local optimistic echoes pendingLocal and queued-N/active synthesized bubbles, to avoid over-undoing) → :fork fully clones the current session → :undo {"count":n} on the new session (verified working) → switch to the new session; APIClient.sessionAction supports an optional body; new undoSession

## 0.7.0 (2026-09-05)
- Added context usage display (chat page status area, e.g. "Context 23.5k/1000k (2%)"): data-source priority ① WS transcript.reset snapshot payload.snapshot.meta.agent contextTokens/maxContextTokens → ② transcript.ops meta.merge agent.contextTokens/maxContextTokens (missing fields keep previous values) → ③ GET /sessions/{id} usage.context_tokens/context_limit as fallback (observed to be all zeros in practice, in which case nothing is shown); WSService gains a .contextUsage event; APIClient gains getSessionUsage
- New "Fork" button in the chat toolbar: same path as /fork (POST :fork; on success switches to the new session via forkTarget); the /fork handling logic is extracted into the public ChatViewModel.fork() for reuse
- New /rename (or /title) command: POST /sessions/{id}/profile with top-level body {"title":"..."} (verified); takes all text after the first space as the new title, and shows usage when the argument is empty; on success refreshes the navigation bar title (sessionTitle changed to @Published) and posts a kimiSessionRenamed notification so MainView silently refreshes the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / command matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Offline speech model switched to on-demand download (aligned with Android): the model is no longer bundled (project.yml drops the Resources/models reference; the directory keeps only .gitkeep); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; Settings gains an "Offline Speech Model" section: model status, editable download URL (UserDefaults voice_model_url, default GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download Offline Model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation unzips to Application Support; new ZIPFoundation SwiftPM dependency); the voice button prompts "Please download the offline model from Settings first" when in offline-only mode without the model downloaded
- Added / command support (intercepted before sending, aligned with macOS/Android): /compact→POST :compact, /archive→:archive (returns to the list on success), /fork→:fork (parses data.id/session_id to switch to the new session), /abort (/stop)→:abort, /new→returns to the list and creates a new session (notifies MainView), /help→command list popup; other inputs starting with / are sent as normal prompts; APIClient gains the generic sessionAction (POST /sessions/{id}:{action}; abortSession refactored to reuse it)

## 0.6.0 (2026-09-05)
- Added self-hosted offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + streaming-zipformer Chinese-English bilingual streaming model (int8, ~189MB, placed at KimiMobile/Resources/models/zipformer-bilingual/, bundled as a folder reference; the directory is in .gitignore and not committed to git); new SpeechOnnx (16kHz mono resampling + streaming feed + endpoint-detection segmentation; interface aligned with SpeechInput)
- Settings "Preferences" gains a speech recognition engine choice (auto-prefer-offline / offline-only / system-only, stored in UserDefaults voice_engine); in auto mode the voice button prefers offline recognition when the model exists, and automatically falls back to SFSpeechRecognizer on load failure (the old SpeechInput code is retained)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content areas are height-capped and scrollable: the submit button at the bottom stays reachable with many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also matches the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also matches the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also matches the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added approval UI: polls GET /sessions/{id}/approvals?status=pending (data.items[] with approval_id/tool_name/action/tool_input_display.summary); approval cards show tool name · action + summary; approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); cards are optimistically removed on submit, with rollback polling on failure
- Added Q&A support: polls GET /sessions/{id}/questions?status=pending (data.items[] with question_id and questions[] (each with id/question/header/options/allow_other)); Q&A cards offer single choice per question (kind=single/option_id) and an "Other" text field when allow_other=true (kind=other/text); submit POST /sessions/{id}/questions/{question_id} (body {"answers":{"<question_id>":{...}}}); plus a "Skip" button (all questions kind=skipped)
- Added interrupt button: when busy, the input bar shows a red stop button that calls POST /sessions/{id}:abort (colon-suffix syntax; server verified to return {"aborted":true}); the send button stays enabled (queueing is still possible while busy)
- Approvals/Q&A are polled on the same cadence as history reconciliation (15s busy / 60s idle, same rhythm as startBusyPolling), fetched once immediately onAppear; fetch failures do not clear existing cards to avoid flicker

## 0.4.9 (2026-08-21)
- The bottom of Settings "Preferences" now shows the version ("Version 0.4.9 (2)"), taken from Bundle.main CFBundleShortVersionString / CFBundleVersion, generated automatically from MARKETING_VERSION / CURRENT_PROJECT_VERSION in project.yml

## 0.4.8 (2026-08-20)
- Fixed "out-of-order conversations": applyHistory reconciliation used to unconditionally stack local echo/queued/executing bubbles at the end of the list, so ordering broke when they predated the latest history entries; ChatMessage already has createdAt; APIClient.listQueuedPrompts now returns QueuedPrompt structs with created_at (both active and queued); after reconciliation the whole list is sorted ascending by createdAt (entries without a timestamp go last)
- Fixed "frozen busy badge on idle sessions": busy polling previously ran only while busy; busyPollTask now reconciles every 15s while busy and every 60s while idle as a fallback (started onAppear, stopped when the view disappears, idempotent against stacking); polling no longer stops when going idle
- applyHistory reconciliation entry gains [Reconcile]-prefixed diagnostic logs: history count, queue/active summary, and the verdict for each local echo (history-confirmed/executing/queued/undelivered/POST in flight)
- Synced fix with the macOS 0.4.8 client

## 0.4.7 (2026-08-20)
- Fixed "no streaming content/status when entering a session while a turn is in progress" (verified against server v0.37.2: late subscribers do not receive that turn's transcript.ops):
  - WSService, when handling transcript.reset, parses payload.snapshot.meta.agent.phase and re-emits a .phase event (with multiple agents and multiple resets only main is taken: agent_id=="main" or meta non-empty); ChatViewModel immediately shows "Working/Thinking" accordingly (phase kind gains tool_call recognition)
  - During busy, history is polled every 15s (including queue reconciliation), with one final refresh when busy clears; stops automatically when the view disappears or goes idle, idempotent against stacking; when work_changed may also be missed, phase events (running/tool_call/streaming start, ended/interrupted stop) serve as the fallback trigger; loadHistory's "don't overwrite while streaming" guard is relaxed to "don't overwrite only when there are streaming frames", so late subscribers with no frames reconcile normally
- Fixed "executing messages shown as queued": the data.active of GET /prompts?status=queued is the currently executing prompt (not in queued[]); APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconciliation, local echoes matching active's text are marked "Executing" (ChatMessage gains isExecuting, shown as small text under the bubble in ChatView) instead of "Queued"/"Undelivered"; when active has no local echo (re-entering a session / restarting the app), an "Executing" bubble is rendered for it

## 0.4.6 (2026-08-20)
- Fixed "stale busy badge in the session list": the list only updated on entry/manual refresh, so the "Running" spinner kept old state; MainView now uses a .task loop that silently refreshes the session list every 30s (updates only sessions, without touching the loading/error bar), stopping automatically via .task cancellation when the view disappears
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped — neither in the queue nor in history): during applyHistory reconciliation, a pendingLocal entry that is in neither history nor queue and is older than 60s is marked "Undelivered (dropped by server)" (ChatMessage gains deliveryFailed) instead of showing "Queued"; in-flight POSTs (<60s) are kept as normal
- ChatView user bubbles gain a red "Undelivered (dropped by server)" warning in small text; queued-N bubbles rebuilt from the server queue are already rebuilt on every reconciliation and disappear naturally when present in neither queue nor history

## 0.4.4 (2026-08-20)
- Fixed "queued messages vanish after re-entering a session / killing the app": v0.4.3's pendingLocal lived only in memory, so queued messages sitting in the server queue were no longer shown in the UI; the server queue is now the source of truth — new GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts); when history is fetched, the queue is fetched and reconciled alongside: echoes confirmed in history are removed, echoes in the queue are marked "Queued", queue entries without a local echo are rendered as queued bubbles (deduplicated by text); when a send returns queued, the local echo is immediately marked "Queued"
- ChatView user bubbles gain a "Queued" marker (small text + spinner under the bubble)

## 0.4.3 (2026-08-20)
- Fixed "queued messages being erased": when busy, POST /prompts returns status="queued", and a queued user message does not enter the GET /messages history until its turn; the local optimistic echo after sending enters pendingLocal pending confirmation, and is removed only when a user message with the same text appears on the server during history refresh, otherwise it stays at the end of the list; the queued status bar hints "Queued…"; on send failure the echo is removed (APIClient.sendPrompt returns status)
- Added tool pipeline visibility: frames with frame.kind=="tool" in WS transcript.ops are shown as tool activity entries in the message stream ("🔧 Bash: date", spinner while running / ✓ when done; summary taken from display.summary ?: inputText ?: input description, newlines stripped and truncated to 80 chars); temporary entries disappear naturally when history refreshes at turn end
- Synced fix with Android 0.4.3

## 0.4.2 (2026-08-15)
- Fixed "sessions disappearing": GET /sessions no longer carries busy=false; sessions that are running or stuck on approval reappear in the list (rows already have a busy spinner marker)
- Synced fix with the Android / macOS clients

## 0.4 (2026-08-15) — First release
- Native SwiftUI client, feature-aligned with Android 0.4.1 / macOS 0.4
- Tailscale gating (healthz probe + onboarding page + automatic retry)
- Session list + workspace switching (default mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential-backoff reconnect)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (preset work machine / Mac laptop; tokens in Keychain)
- Session mode bar: Plan/Swarm/Permission/Model/Goal; mode state persisted locally per session (UserDefaults) + prompts carry mode fields (official mechanism)
- Phantom message filtering (<system-reminder> etc.); 200-wrapped error parsing and display to the user

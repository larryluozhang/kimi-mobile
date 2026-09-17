# Changelog

## 0.7.3 (2026-09-10)
- Image attachments in chat: new photo button (PhotosPicker) in the input bar uploads the picked image immediately (`APIClient.uploadFile`, POST /api/v1/files, hand-written multipart body with field `file` → data.id) and keeps a small preview with a remove button until sent; sending now supports image+text mixed content — `APIClient.sendPrompt` takes an optional `imageFileId` and prepends a `{"type":"image","source":{"kind":"file","file_id":...}}` block to the content array (image-only messages with empty text are allowed; the send button stays enabled while a pending image exists). Picked photos are normalized to JPEG (compression 0.85) so HEIC shots stay compatible with the server and the local preview
- Message bubbles render image blocks: `APIClient.getMessages` now extracts `file_id` from `image` content blocks into the new `ChatMessage.imageFileId` (pure-image messages are no longer filtered out), and `MessageBubble` shows them via the new `AuthedImage` view (RemoteImage.swift) — since AsyncImage cannot send headers, images are fetched with `APIClient.fetchFile` (GET /api/v1/files/{file_id} with Authorization Bearer) returning raw Data → UIImage, backed by a simple in-memory NSCache (`ImageCache`); local optimistic echoes, history reconciliation matching (text + imageFileId), and queued/executing badges all work with image messages
- Model picker now uses a dynamic server-side list: new `APIClient.listModels()` (GET /api/v1/models → data.items[] with provider/model/display_name/max_context_size); ChatViewModel fetches on session entry (`loadModels()`), ModeBarView renders the dynamic list (display_name preferred, annotated with context size) and falls back to the built-in `Constants.availableModels` presets when the fetch fails or is empty; the currently selected model is appended if missing from the list so the Picker selection never dangles. SettingsView's global model row switches from free-text input to the same dynamic list (refetched when the active host profile changes), falling back to the TextField when unavailable
- Context limit now follows the current model: `ChatViewModel.effectiveContextLimit` resolves the session model's `max_context_size` from the server model list, with priority current-model max_context_size > WS maxContextTokens > fallback 1048576; `contextUsageText` uses it, so the limit updates automatically after a model switch
- Auto-scroll no longer yanks reading position: ChatView now tracks whether the last message bubble is visible in the LazyVStack (`isAtBottom`) and only scrolls to bottom on refresh/streaming updates when the user is already at the bottom; pagination prepend anchor scrolling (scrollAnchorAfterPrepend) is unchanged and takes precedence; sending your own message is treated as an explicit return-to-bottom intent

## 0.7.2 (2026-09-05)
- Added history message pagination: a "Load earlier messages" button at the top of the chat page; tapping it fetches the previous page by before_id and prepends it (GET /messages?page_size=100 only returns the most recent 100 raw messages including tool ones, so after filtering only a few visible bubbles may remain; before_id verified working in practice); APIClient.getMessages supports an optional beforeId and returns MessagesPage (filtered messages + the oldest raw message id/timestamp of this page as the cursor + a rough hasMore = whether this page's raw item count reached page_size); the cursor takes the minimum of the oldest raw message of each page (monotonically toward older; the latest page brought back by polling never moves the cursor back); earlierHistory is deduplicated by id and merged with the latest page for rendering (applyHistory sorts by createdAt so prepend happens naturally; pendingLocal/queued/active synthesized bubbles are unaffected; echo/queue text matching still only applies to the latest page, avoiding old messages with identical text being misjudged as "confirmed"); loading state hints (Loading.../No more; a page whose raw item count < page_size is treated as the end); after prepend, scrolling anchors to the original first message instead of jumping to the bottom

## 0.7.1 (2026-09-05)
- Fixed context usage display: limit now comes from WS maxContextTokens, used only when >0, otherwise falls back to 1048576 (no longer hides the entire line when limit is missing or 0); format fixed to "usage/limit (percentage)", always showing the percentage; fixed a decimal formatting bug (formatTokens now formats with %.1f first and then trims the ".0" suffix, so rounding to an integer no longer leaks decimals like "1000.0k")
- Added "Fork from here": long-press contextMenu entry on user bubbles; counts the number n of user messages after that message already in server-side history (excluding local optimistic echoes pendingLocal and queued-N/active synthesized bubbles, to avoid undoing too many) → :fork fully clones the current session → :undo {"count":n} on the new session (verified working in practice) → switch to the new session; APIClient.sessionAction supports an optional body; added undoSession

## 0.7.0 (2026-09-05)
- Added context usage display (in the chat page status area, e.g. "Context 23.5k/1000k (2%)"): data source priority ① contextTokens/maxContextTokens from WS transcript.reset snapshot payload.snapshot.meta.agent → ② agent.contextTokens/maxContextTokens from transcript.ops meta.merge (missing fields keep the old value) → ③ fallback to usage.context_tokens/context_limit from GET /sessions/{id} (in practice these may all be 0, in which case nothing is shown); WSService adds a .contextUsage event, APIClient adds getSessionUsage
- Added a "Fork" button to the chat page toolbar: same path as /fork (POST :fork, switches to the new session via forkTarget on success); the /fork handling logic is extracted into the public ChatViewModel.fork() for reuse
- Added /rename (or /title) command: POST /sessions/{id}/profile with top-level body {"title":"..."} (verified in practice); takes all remaining text after the first space as the new title; empty argument shows usage; on success refreshes the navigation bar title (sessionTitle changed to @Published) and posts a kimiSessionRenamed notification so MainView silently refreshes the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / commands matching by the first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (aligned with Android): the model is no longer bundled (project.yml drops the Resources/models reference; the directory keeps only .gitkeep); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; the settings page adds an "Offline speech model" section: model status, editable download URL (UserDefaults voice_model_url, default GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download offline model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation extracts into Application Support; new ZIPFoundation SwiftPM dependency); the voice button in offline-only mode without the model downloaded prompts "Please download the offline model from the settings page first"
- Added / command support (intercepted before sending, aligned with macOS/Android): /compact→POST :compact, /archive→:archive (returns to the list on success), /fork→:fork (parses data.id/session_id and switches to the new session), /abort (/stop)→:abort, /new→returns to the list and creates a new session (notifies MainView), /help→command list popup; other input starting with / is sent as a normal prompt; APIClient adds the generic method sessionAction (POST /sessions/{id}:{action}, abortSession now reuses it)

## 0.6.0 (2026-09-05)
- Added self-hosted offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + streaming-zipformer Chinese-English bilingual streaming model (int8, about 189MB, placed in KimiMobile/Resources/models/zipformer-bilingual/, bundled as a folder reference; the directory is added to .gitignore and not committed to git); added SpeechOnnx (16kHz mono resampling + streaming feed + endpoint detection segmentation, interface aligned with SpeechInput)
- The settings page "Preferences" adds a speech recognition engine selection (auto prefer offline / offline only / system only, stored in UserDefaults voice_engine); the voice button in auto mode prefers offline recognition when the model exists, and automatically falls back to SFSpeechRecognizer if loading fails (the old SpeechInput code is kept)

## 0.5.2 (2026-09-02)
- Question card/dialog content area now height-limited and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added approval UI: polls GET /sessions/{id}/approvals?status=pending (data.items[] containing approval_id/tool_name/action/tool_input_display.summary); the approval card shows tool name · action + summary; approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); the card is optimistically removed on submit, and polling rolls it back on failure
- Added question support: polls GET /sessions/{id}/questions?status=pending (data.items[] containing question_id, questions[] (each with id/question/header/options/allow_other)); the question card does per-question single choice (kind=single/option_id), and provides an "Other" text box when allow_other=true (kind=other/text); submit POST /sessions/{id}/questions/{question_id} (body {"answers":{"<question_id>":{...}}}), plus a "Skip" button (all questions kind=skipped)
- Added interrupt button: when busy, the input bar shows a red stop button; tapping it calls POST /sessions/{id}:abort (colon-suffix syntax; the server verified to return {"aborted":true}); the send button stays enabled (queuing is still possible while busy)
- Approvals/questions are polled in the same cycle as history reconciliation (busy 15s / idle 60s, same rhythm as startBusyPolling), fetched once immediately onAppear; fetch failures do not clear existing cards to avoid flickering

## 0.4.9 (2026-08-21)
- The settings page "Preferences" bottom adds a version display ("Version 0.4.9 (2)"), taken from Bundle.main's CFBundleShortVersionString / CFBundleVersion, auto-generated from project.yml's MARKETING_VERSION / CURRENT_PROJECT_VERSION

## 0.4.8 (2026-08-20)
- Fixed "conversation order scrambled": applyHistory reconciliation previously unconditionally appended local echo/queued/executing bubbles at the end of the list, so order broke when they were earlier than the latest history entry; ChatMessage already has createdAt, and APIClient.listQueuedPrompts now returns QueuedPrompt structures with created_at (both active/queued include it); after reconciliation completes, the whole list is sorted ascending by createdAt (entries without a timestamp sort last)
- Fixed "idle session badge state frozen": busy polling previously only ran while busy; busyPollTask now polls every 15s when busy and every 60s when idle as a fallback reconciliation (started onAppear, stopped when the view disappears, idempotent against stacking); transitioning to idle no longer stops polling
- The applyHistory reconciliation entry point adds [Reconcile]-prefixed diagnostic logs: history entry count, queue/active summary, and the verdict for each local echo (confirmed by history / executing / queued / not delivered / POST in flight)
- Synced fix with the macOS client 0.4.8

## 0.4.7 (2026-08-20)
- Fixed "no streaming content/status when entering a session while a turn is in progress" (verified against v0.37.2 server: late subscribers do not receive that turn's transcript.ops):
  - WSService, when handling transcript.reset, parses payload.snapshot.meta.agent.phase and re-emits a .phase event (with multiple agents and multiple resets, only main is taken: agent_id=="main" or meta non-empty); ChatViewModel uses this to immediately show "Working/Thinking" (phase kind adds tool_call recognition)
  - While busy, history is polled every 15s (including queue reconciliation), with one final refresh when busy clears; it stops automatically when the view disappears or goes idle, idempotent against stacking; when work_changed may also not be received, phase events (running/tool_call/streaming start, ended/interrupted stop) serve as the fallback trigger; loadHistory's "do not overwrite while streaming" guard is relaxed to "only do not overwrite when there are streaming frames", so late subscribers with no frames reconcile as usual
- Fixed "messages currently executing shown as queued": data.active from GET /prompts?status=queued is the prompt currently executing (not in queued[]); APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconciliation, local echoes with the same text as active are marked "Executing" (ChatMessage adds isExecuting, shown as small text under the ChatView bubble), no longer marked "Queued"/"Not delivered"; active entries with no local echo (re-entering a session / restarting the app) get an "Executing" bubble rendered for them

## 0.4.6 (2026-08-20)
- Fixed "stale busy badge in the session list": the list previously only updated on entry/manual refresh, so the "Running" spinner would linger in an old state; MainView now uses a .task loop to silently refresh the session list every 30s (only updates sessions, does not touch loading/error bar), automatically stopping via .task cancellation when the view disappears
- Fallback for queued messages silently dropped by the server (upstream bug #3127: under phantom busy, queued prompts are silently dropped — neither in the queue nor in history): during applyHistory reconciliation, a pendingLocal entry that is in neither history nor queue and is over 60s old → marked "Not delivered (dropped by server)" (ChatMessage adds deliveryFailed), no longer shown as "Queued"; POST in flight (<60s) is kept normally
- ChatView user bubbles add a red small-text warning "Not delivered (dropped by server)"; queued-N bubbles rebuilt from the server queue are already rebuilt on every reconciliation, and naturally disappear when neither the queue nor history has them

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappearing after re-entering a session / killing the app": v0.4.3's pendingLocal existed only in memory, so queued messages were in the server queue but no longer shown in the UI; changed to treat the server queue as the source of truth — added GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts); when fetching history, the queue is fetched and reconciled at the same time: echoes confirmed by history are removed, echoes in the queue are marked "Queued", and queue entries with no local echo are rendered as queued bubbles (deduplicated by text); when send returns queued, the local echo is immediately marked "Queued"
- ChatView user bubbles add a "Queued" marker (small text under the bubble + spinner)

## 0.4.3 (2026-08-20)
- Fixed "queued messages being erased": when busy, POST /prompts returns status="queued", and queued user messages do not enter GET /messages history until their turn to execute; after sending, the local optimistic echo enters pendingLocal pending confirmation, and is removed only when a user message with the same text appears on the server during history refresh, otherwise kept at the end of the list; the queued status bar shows "Queuing..."; send failure removes the echo (APIClient.sendPrompt returns status)
- Added tool activity visibility: frames with frame.kind=="tool" in WS transcript.ops show tool activity entries in the message stream ("🔧 Bash: date", spinner while running / ✓ when done; summary taken from display.summary ?: inputText ?: input description, newlines removed, truncated to 80 characters); temporary entries disappear naturally when history refreshes at turn end
- Synced fix with Android 0.4.3

## 0.4.2 (2026-08-15)
- Fixed "sessions lost": GET /sessions no longer carries busy=false, so running/approval-stuck sessions are shown in the list again (list rows already have a busy spinner marker)
- Synced fix with the Android / macOS clients

## 0.4 (2026-08-15) — First release
- Native SwiftUI client, feature-aligned with Android 0.4.1 / macOS 0.4
- Tailscale gating (healthz probe + onboarding page + automatic retry)
- Session list + workspace switching (default mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential backoff reconnection)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (presets for work machine / Mac laptop, tokens stored in Keychain)
- Session mode bar: plan/Swarm/permission/model/goal; mode state persisted locally per session (UserDefaults) + prompts carry mode fields (official mechanism)
- Phantom message filtering (<system-reminder> etc.); 200-wrapped error parsing and display to the user

# Changelog

## 0.7.3 (2026-09-10)
- Image attachments in chat: new photo button (PhotosPicker) in the input bar uploads the picked image immediately (`APIClient.uploadFile`, POST /api/v1/files, hand-written multipart body with field `file` → data.id) and keeps a small preview with a remove button until sent; sending now supports image+text mixed content — `APIClient.sendPrompt` takes an optional `imageFileId` and prepends a `{"type":"image","source":{"kind":"file","file_id":...}}` block to the content array (image-only messages with empty text are allowed; the send button stays enabled while a pending image exists). Picked photos are normalized to JPEG (compression 0.85) so HEIC shots stay compatible with the server and the local preview
- Message bubbles render image blocks: `APIClient.getMessages` now extracts `file_id` from `image` content blocks into the new `ChatMessage.imageFileId` (pure-image messages are no longer filtered out), and `MessageBubble` shows them via the new `AuthedImage` view (RemoteImage.swift) — since AsyncImage cannot send headers, images are fetched with `APIClient.fetchFile` (GET /api/v1/files/{file_id} with Authorization Bearer) returning raw Data → UIImage, backed by a simple in-memory NSCache (`ImageCache`); local optimistic echoes, history reconciliation matching (text + imageFileId), and queued/executing badges all work with image messages
- Model picker now uses a dynamic server-side list: new `APIClient.listModels()` (GET /api/v1/models → data.items[] with provider/model/display_name/max_context_size); ChatViewModel fetches on session entry (`loadModels()`), ModeBarView renders the dynamic list (display_name preferred, annotated with context size) and falls back to the built-in `Constants.availableModels` presets when the fetch fails or is empty; the currently selected model is appended if missing from the list so the Picker selection never dangles. SettingsView's global model row switches from free-text input to the same dynamic list (refetched when the active host profile changes), falling back to the TextField when unavailable
- Context limit now follows the current model: `ChatViewModel.effectiveContextLimit` resolves the session model's `max_context_size` from the server model list, with priority current-model max_context_size > WS maxContextTokens > fallback 1048576; `contextUsageText` uses it, so the limit updates automatically after a model switch

## 0.7.2 (2026-09-05)
- Added history message pagination: a "Load earlier messages" button at the top of the chat page; tapping it fetches the previous page by before_id and prepends it (GET /messages?page_size=100 returns only the latest 100 raw messages including tool, so after filtering only a few visible bubbles may remain; before_id verified effective); APIClient.getMessages supports an optional beforeId and returns MessagesPage (filtered messages + this page's oldest raw message id/time as cursor + hasMore roughly judged by whether this page's raw items count reaches page_size); the cursor takes the minimum of each page's oldest raw message (monotonically moving older; the latest page brought back by polling never moves the cursor back); earlierHistory is merged with the latest page after id-based dedup (applyHistory sorts by createdAt so prepending is natural, and pendingLocal/queued/active synthetic bubbles are unaffected; echo/queue text matching still only targets the latest page, avoiding old messages with the same text being misjudged as "confirmed"); loading state hints ("Loading…"/"No more"; a page whose raw items count < page_size is considered the top); after prepending, scrolling anchors to the original first message instead of jumping to the bottom

## 0.7.1 (2026-09-05)
- Fixed the context usage display: limit takes WS maxContextTokens, used only when >0, otherwise falls back to 1048576 (the row no longer disappears entirely when limit is missing/0); format fixed as "usage/limit (percent)", percent always shown; fixed a decimal formatting bug (formatTokens now formats with %.1f first then strips the ".0" suffix, so rounding to an integer no longer leaks decimals like "1000.0k")
- Added "Fork from here": a long-press contextMenu entry on user bubbles; counts the user messages n after this message that are already in server-side history (excluding local optimistic echoes pendingLocal and queued-N/active synthetic bubbles, to avoid over-undoing) → :fork full clone of the current session → :undo {"count":n} on the new session (verified effective) → switch to the new session; APIClient.sessionAction supports an optional body; new undoSession

## 0.7.0 (2026-09-05)
- Added context usage display (chat page status area, e.g. "Context 23.5k/1000k (2%)"): data source priority 1) contextTokens/maxContextTokens from the WS transcript.reset snapshot payload.snapshot.meta.agent → 2) agent.contextTokens/maxContextTokens from transcript.ops meta.merge (missing fields keep their old values) → 3) fallback GET /sessions/{id} usage.context_tokens/context_limit (observed to possibly be all 0 — not shown in that case); WSService gains a .contextUsage event, APIClient gains getSessionUsage
- Chat page toolbar gains a "Fork" button: same path as /fork (POST :fork, switching to the new session via forkTarget on success); the /fork handling logic extracted as the public ChatViewModel.fork() for reuse
- Added /rename (or /title) command: POST /sessions/{id}/profile with a top-level {"title":"..."} in the body (verified); takes all remaining text after the first space as the new title, shows usage when the argument is empty; on success refreshes the navigation bar title (sessionTitle changed to @Published) and posts the kimiSessionRenamed notification so MainView silently refreshes the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / command matching by first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (aligned with Android): the model is no longer bundled (project.yml drops the Resources/models reference, the directory keeps only .gitkeep); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; the settings page gains an "Offline speech model" section: model status, editable download URL (UserDefaults voice_model_url, default GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download offline model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation unzips to Application Support; new ZIPFoundation SwiftPM dependency); the voice button in offline-only mode with the model not downloaded prompts "Please download the offline model from the settings page first"
- Added / command support (intercepted before sending, aligned with macOS/Android): /compact → POST :compact, /archive → :archive (returns to the list on success), /fork → :fork (parses data.id/session_id and switches to the new session), /abort (/stop) → :abort, /new → returns to the list and creates a new session (notifies MainView), /help → command list dialog; other input starting with / is sent as a regular prompt; APIClient gains the generic method sessionAction (POST /sessions/{id}:{action}; abortSession refactored to reuse it)

## 0.6.0 (2026-09-05)
- Added bundled offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + streaming-zipformer bilingual Chinese-English streaming model (int8, ~189MB, placed in KimiMobile/Resources/models/zipformer-bilingual/, bundled as a folder reference, directory added to .gitignore and not committed to git); new SpeechOnnx (16kHz mono resampling + streaming feed + endpoint detection segmentation, interface aligned with SpeechInput)
- Settings page "Preferences" gains a speech recognition engine choice (auto prefer offline / offline only / system only, stored in UserDefaults voice_engine); in auto mode the voice button prefers offline recognition when the model exists, automatically falling back to SFSpeechRecognizer on load failure (old SpeechInput code kept)

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area height-limited and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being shown as user messages: isSystemInjected adds the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added approval UI: polls GET /sessions/{id}/approvals?status=pending (data.items[] contains approval_id/tool_name/action/tool_input_display.summary); the approval card shows tool name · action + summary, and the approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); the card is optimistically removed on submit, rolled back to polling on failure
- Added Q&A support: polls GET /sessions/{id}/questions?status=pending (data.items[] contains question_id, questions[] (each with id/question/header/options/allow_other)); the Q&A card does single choice per question (kind=single/option_id); when allow_other=true an "Other" text field is provided (kind=other/text); submit POST /sessions/{id}/questions/{question_id} (body {"answers":{"<questionId>":{...}}}); there is also a "Skip" button (kind=skipped for all questions)
- Added interrupt button: when busy, the input bar shows a red stop button; tapping it calls POST /sessions/{id}:abort (colon-suffix syntax, server observed to return {"aborted":true}); the send button stays usable (still queueable while busy)
- Approvals/Q&A poll on the same cycle as history reconciliation (busy 15s / idle 60s, startBusyPolling same cadence), fetched once immediately on onAppear; fetch failures do not clear existing cards to avoid flicker

## 0.4.9 (2026-08-21)
- Settings page "Preferences" bottom shows the version number ("Version 0.4.9 (2)"), taken from Bundle.main's CFBundleShortVersionString / CFBundleVersion, automatically generated with project.yml's MARKETING_VERSION / CURRENT_PROJECT_VERSION

## 0.4.8 (2026-08-20)
- Fixed "conversation order corruption": applyHistory reconciliation previously piled local echo/queued/running bubbles unconditionally at the end of the list, so order broke when they were earlier than the latest history entry; ChatMessage already has createdAt, APIClient.listQueuedPrompts now returns a QueuedPrompt struct with created_at (both active and queued), and after reconciliation the whole list is sorted ascending by createdAt (entries without a timestamp go last)
- Fixed "idle session badge state frozen": busy polling previously only ran while busy; busyPollTask now runs a fallback reconciliation round every 15s while busy and every 60s while idle (started on onAppear, stopped when the view disappears, idempotent against stacking); polling no longer stops when going idle
- applyHistory reconciliation entry gains [Reconcile]-prefixed diagnostic logs: history count, queue/active summary, and the verdict for each local echo (history-confirmed/running/queued/undelivered/POST in flight)
- Synced fixes with the macOS 0.4.8 client

## 0.4.7 (2026-08-20)
- Fixed "entering a session mid-turn shows no streaming content/status at all" (observed on v0.37.2 server: late subscribers receive none of that turn's transcript.ops):
  - WSService, when handling transcript.reset, parses payload.snapshot.meta.agent.phase and re-emits a .phase event (among multiple resets from multiple agents, takes main only: agent_id=="main" or non-empty meta); ChatViewModel accordingly shows "Working/Thinking" immediately (phase kind gains tool_call recognition)
  - Polls history every 15s while busy (including queue reconciliation), with one final refresh when busy disappears; stops automatically when the view disappears or goes idle, idempotent against stacking; when work_changed may also be unreceived, phase events (running/tool_call/streaming start, ended/interrupted stop) trigger it as a fallback; loadHistory's "don't overwrite while streaming" guard is relaxed to "don't overwrite only when there are streaming frames", so late subscribers without frames reconcile normally
- Fixed "messages being executed shown as queued": GET /prompts?status=queued's data.active is the currently executing prompt (not in queued[]); APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconciliation, a local echo matching active text is labeled "Running" (ChatMessage gains isExecuting, small-text marker under the ChatView bubble), no longer labeled "Queued"/"Undelivered"; active entries without a local echo (re-entering a session/restarting the app) are additionally rendered as "Running" bubbles

## 0.4.6 (2026-08-20)
- Fixed "stale busy badge in the session list": the list previously only updated on entry/manual refresh, so the "Running" spinner stayed in an old state; MainView changed to a .task loop that silently refreshes the session list every 30s (updates sessions only, does not touch the loading/error bars), stopping automatically with .task cancellation when the view disappears
- Fallback for queued messages dropped by the server (upstream bug #3127: queued prompts under phantom busy are silently discarded, appearing in neither the queue nor history): during applyHistory reconciliation, a pendingLocal entry that is in neither history nor queue and is older than 60s → marked "Undelivered (dropped by server)" (ChatMessage gains deliveryFailed), no longer shown as "Queued"; POST in flight (<60s) is kept normally
- ChatView user bubbles gain an "Undelivered (dropped by server)" red small-text warning; queued-N bubbles rebuilt from the server queue are rebuilt with each reconciliation anyway and disappear naturally when absent from both queue and history

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session/killing the app": v0.4.3's pendingLocal was in-memory only, so queued messages were in the server queue but no longer shown in the UI; changed to use the server queue as the source of truth — new GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts), fetching the queue together with history and reconciling: echoes confirmed in history are removed, echoes in the queue are labeled "Queued", queue entries without a local echo are additionally rendered as queued bubbles (deduplicated by text); when sending returns queued the local echo is immediately labeled "Queued"
- ChatView user bubbles gain a "Queued" marker (small text under the bubble + spinner)

## 0.4.3 (2026-08-20)
- Fixed "queued messages wiped": when busy, POST /prompts returns status="queued", and a queued user message does not enter GET /messages history until it runs; the local optimistic echo after sending enters pendingLocal pending confirmation, and is removed on history refresh only when the server shows a user message with the same text, otherwise kept at the end of the list; the queued status bar shows "Queued…"; the echo is removed on send failure (APIClient.sendPrompt returns status)
- Added tool activity stream visibility: frames with frame.kind=="tool" in WS transcript.ops show tool activity entries in the message stream ("🔧 Bash: date", running spinner / done marked ✓, summary takes display.summary ?: inputText ?: input description, newlines stripped and truncated to 80 chars); temporary entries disappear naturally on history refresh when the turn ends
- Synced fixes with Android 0.4.3

## 0.4.2 (2026-08-15)
- Fixed "sessions lost": GET /sessions no longer passes busy=false, so running/approval-blocked sessions are shown in the list again (the list rows already have a busy spinner marker)
- Synced fixes with the Android / macOS clients

## 0.4 (2026-08-15) — First release
- SwiftUI native client, feature parity with Android 0.4.1 / macOS 0.4
- Tailscale gate (healthz probe + onboarding page + automatic retry)
- Session list + workspace switching (default mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential backoff reconnect)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (presets: 146 work machine / Mac laptop, tokens stored in Keychain)
- Session mode bar: plan/Swarm/permission/model/goal; mode state persisted locally per session (UserDefaults) + prompts carry mode fields (official mechanism)
- Phantom message filtering (<system-reminder> etc.); 200-envelope error parsing shown to the user

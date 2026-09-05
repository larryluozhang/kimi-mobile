# Changelog

## 0.7.1 (2026-09-05)
- Fixed context usage display: the limit comes from WS maxContextTokens, used only when >0, otherwise falls back to 1048576 (no longer hiding the whole entry when the limit is missing/0); the format is pinned to "usage/limit (percent)", always including the percentage; fixed a decimal formatting bug (formatTokens now formats with %.1f first, then strips the ".0" suffix, so values rounding to an integer no longer leak decimals like "1000.0k")
- Added "Fork from here": a long-press contextMenu entry on user bubbles; count n = user messages after this one that are already in the server-side history (excluding local optimistic echoes in pendingLocal and queued-N/active synthetic bubbles, to avoid over-undoing) → :fork to fully clone the current session → :undo {"count":n} on the new session (verified working) → switch to the new session; APIClient.sessionAction supports an optional body; added undoSession

## 0.7.0 (2026-09-05)
- Added context usage display (chat page status area, e.g. "Context 23.5k/1000k (2%)"): data source priority 1. contextTokens/maxContextTokens from the WS transcript.reset snapshot payload.snapshot.meta.agent → 2. agent.contextTokens/maxContextTokens from transcript.ops meta.merge (missing fields keep the old value) → 3. fallback GET /sessions/{id} usage.context_tokens/context_limit (observed to sometimes be all 0, in which case nothing is shown); WSService gains a .contextUsage event; APIClient gains getSessionUsage
- Added a "Fork" button to the chat page toolbar: same path as /fork (POST :fork, on success switches to the new session via forkTarget); the /fork handling was extracted into a public ChatViewModel.fork() for reuse
- Added /rename (or /title) command: POST /sessions/{id}/profile with top-level {"title":"..."} in the body (verified); everything after the first space becomes the new title; empty argument shows a usage hint; on success the navigation bar title refreshes (sessionTitle changed to @Published) and a kimiSessionRenamed notification tells MainView to silently refresh the list; /help updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / commands to match on the first token (/fork with arguments is no longer sent as a regular message)

## 0.6.1 (2026-09-05)
- Offline speech model changed to on-demand download (aligned with Android): the model is no longer bundled (project.yml drops the Resources/models reference, leaving only .gitkeep in the directory); SpeechOnnx.modelAvailable now checks the runtime directory Application Support/models/zipformer-bilingual/; Settings gains an "Offline speech model" section: model status, editable download URL (UserDefaults voice_model_url, defaulting to GitHub Releases v0.6.1-models/model-zipformer-bilingual.zip), a "Download offline model" button + progress text (ModelDownloadManager: URLSessionDownloadTask downloads to tmp → ZIPFoundation extracts into Application Support; adds the ZIPFoundation SwiftPM dependency); the voice button in offline-only mode without the model downloaded prompts "Please download the offline model in Settings first"
- Added / command support (intercepted before sending, aligned with macOS/Android): /compact → POST :compact, /archive → :archive (returns to the list on success), /fork → :fork (parses data.id/session_id and switches to the new session), /abort (/stop) → :abort, /new → return to the list and create a new session (notifies MainView), /help → command list popup; other /-prefixed input is sent as a regular prompt; APIClient gains the generic method sessionAction (POST /sessions/{id}:{action}; abortSession refactored to reuse it)

## 0.6.0 (2026-09-05)
- Added bundled offline speech recognition: sherpa-onnx (SwiftPM dependency, Apache-2.0) + the streaming-zipformer bilingual Chinese-English streaming model (int8, ~189MB, placed at KimiMobile/Resources/models/zipformer-bilingual/, bundled as a folder reference; the directory is in .gitignore and not committed to git); added SpeechOnnx (16kHz mono resampling + streaming feed + endpoint-based segmentation, interface aligned with SpeechInput)
- Settings "Preferences" gains a speech recognition engine choice (auto-prefer-offline / offline-only / system-only, stored in UserDefaults voice_engine); in auto mode the voice button prefers offline recognition when the model exists, automatically falling back to SFSpeechRecognizer on load failure (old SpeechInput code retained)

## 0.5.2 (2026-09-02)
- Question card/dialog content area is now height-capped and scrollable: the submit button at the bottom stays reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages: isSystemInjected now also covers the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- Added approval UI: polls GET /sessions/{id}/approvals?status=pending (data.items[] contains approval_id/tool_name/action/tool_input_display.summary); the approval card shows tool name · action + summary; approve/reject buttons POST /sessions/{id}/approvals/{approval_id} (body {"decision":"approved"|"rejected"}); cards are optimistically removed on submit, with rollback to polling on failure
- Added question support: polls GET /sessions/{id}/questions?status=pending (data.items[] contains question_id and questions[] (each with id/question/header/options/allow_other)); the question card renders single-choice per question (kind=single/option_id), offering an "Other" text field when allow_other=true (kind=other/text); submit via POST /sessions/{id}/questions/{question_id} (body {"answers":{"<question id>":{...}}}), plus a "Skip" button (all questions kind=skipped)
- Added interrupt button: a red stop button appears in the input bar while busy; tapping it calls POST /sessions/{id}:abort (colon-suffix syntax; the server is observed to return {"aborted":true}); the send button stays enabled (messages can still be queued while busy)
- Approvals/questions are polled on the same cycle as history reconciliation (15s busy / 60s idle, same cadence as startBusyPolling), with one immediate fetch on first onAppear; fetch failures do not clear existing cards, avoiding flicker

## 0.4.9 (2026-08-21)
- The Settings "Preferences" footer now shows the version ("Version 0.4.9 (2)"), taken from Bundle.main's CFBundleShortVersionString / CFBundleVersion, automatically generated from project.yml's MARKETING_VERSION / CURRENT_PROJECT_VERSION

## 0.4.8 (2026-08-20)
- Fixed "out-of-order conversations": applyHistory reconciliation used to stack local echo/queued/executing bubbles unconditionally at the end of the list, scrambling the order when they were older than the latest history entry; ChatMessage already has createdAt, and APIClient.listQueuedPrompts now returns a QueuedPrompt struct carrying created_at (for both active/queued); after reconciliation the whole list is sorted by createdAt ascending (items without a timestamp go last)
- Fixed "badge states of idle sessions freezing": busy polling used to run only while busy; busyPollTask now reconciles every 15s while busy and every 60s while idle as a fallback (started on onAppear, stopped when the view disappears, idempotent against stacking); polling no longer stops when turning idle
- The applyHistory reconciliation entry gains [Reconcile]-prefixed diagnostic logs: history count, queue/active summary, and the verdict for each local echo (confirmed in history / executing / queued / undelivered / POST in flight)
- Synced with the macOS 0.4.8 fixes

## 0.4.7 (2026-08-20)
- Fixed "no streaming content or status when entering a session mid-turn" (observed on the v0.37.2 server: late subscribers receive none of that turn's transcript.ops):
  - WSService parses payload.snapshot.meta.agent.phase when handling transcript.reset and re-emits a .phase event (among multiple resets from multiple agents, only main is used: agent_id=="main" or non-empty meta); ChatViewModel immediately shows "working/thinking" accordingly (phase kind recognition now includes tool_call)
  - Poll history every 15s while busy (including queue reconciliation), with one final refresh when busy clears; stops automatically when the view disappears or the session turns idle; idempotent against stacking; since work_changed may also be missed, phase events (running/tool_call/streaming start it, ended/interrupted stop it) serve as the fallback trigger; loadHistory's "don't overwrite while streaming" guard was relaxed to "don't overwrite only when streaming frames exist", so frame-less late subscribers reconcile as usual
- Fixed "executing messages shown as queued": GET /prompts?status=queued's data.active is the currently executing prompt (not inside queued[]); APIClient.listQueuedPrompts now returns (active, queued); during applyHistory reconciliation, a local echo with text matching active is marked "executing" (ChatMessage gains isExecuting; ChatView shows a sub-label under the bubble), no longer marked "queued"/"undelivered"; active entries without a local echo (re-entering a session / app restart) are additionally rendered as "executing" bubbles

## 0.4.6 (2026-08-20)
- Fixed "stale busy badges in the session list": the list used to update only on entry/manual refresh, so the "running" spinner could stay in an old state; MainView now silently refreshes the session list every 30s in a .task loop (updates only sessions, leaving loading/error bars untouched), stopping automatically via .task cancellation when the view disappears
- Fallback for queued messages dropped by the server (upstream bug #3127: under phantom-busy, queued prompts are silently dropped — in neither the queue nor history): during applyHistory reconciliation, a pendingLocal entry in neither history nor queue that is over 60s old → marked "undelivered (dropped by server)" (ChatMessage gains deliveryFailed), no longer showing "queued"; POSTs still in flight (<60s) are kept as-is
- ChatView user bubbles gain a red "undelivered (dropped by server)" warning label; queued-N bubbles rebuilt from the server queue are rebuilt with each reconciliation anyway and disappear naturally when in neither queue nor history

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session / killing the app": v0.4.3's pendingLocal lives only in memory, so queued messages sat in the server queue but were no longer shown in the UI; now the server queue is the source of truth — added GET /sessions/{id}/prompts?status=queued (APIClient.listQueuedPrompts); history fetches also pull the queue for reconciliation: echoes confirmed in history are removed, echoes in the queue are marked "queued", and queue entries without a local echo are additionally rendered as queued bubbles (deduplicated by text); when sending returns queued, the local echo is immediately marked "queued"
- ChatView user bubbles gain a "queued" marker (sub-label + spinner under the bubble)

## 0.4.3 (2026-08-20)
- Fixed "queued messages being wiped": when busy, POST /prompts returns status="queued" and the queued user message does not enter the GET /messages history until its turn; the local optimistic echo after sending enters pendingLocal awaiting confirmation, and is removed only when a user message with identical text appears server-side on history refresh, otherwise kept at the end of the list; the status bar shows "Queued..." when queued; echoes are removed on send failure (APIClient.sendPrompt returns status)
- Tool activity is now visible: WS transcript.ops frames with frame.kind=="tool" show as tool activity entries in the message stream ("🔧 Bash: date", spinner while running / ✓ when done; the summary comes from display.summary ?: inputText ?: input, newlines stripped and truncated to 80 chars); temporary entries disappear naturally on history refresh at turn end
- Synced with the Android 0.4.3 fixes

## 0.4.2 (2026-08-15)
- Fixed "sessions disappearing": GET /sessions no longer passes busy=false, so running/approval-stuck sessions show in the list again (list rows already have a busy spinner marker)
- Synced with the Android / macOS fixes

## 0.4 (2026-08-15) — First release
- Native SwiftUI client, feature-parity with Android 0.4.1 / macOS 0.4
- Tailscale gating (healthz probe + onboarding page + automatic retry)
- Session list + workspace switching (defaults to the mobile workspace)
- WS streaming chat (full transcript.ops protocol; ping/pong; exponential-backoff reconnect)
- Voice input (SFSpeechRecognizer zh-CN)
- Multi-host profiles (preset My Server / My Mac; tokens stored in the Keychain)
- Session mode bar: plan/Swarm/permission/model/goal; mode state persisted locally per session (UserDefaults) + mode fields riding along with prompts (official mechanism)
- Phantom message filtering (<system-reminder> etc.); errors wrapped in HTTP 200 are parsed and shown to the user

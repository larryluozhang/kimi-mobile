# Changelog

## 0.7.4 (2026-09-18)
- In-app update reminders and auto-upgrade: app-latest channel (pinned GitHub Releases tag, latest.json manifest + APK/DMG assets, constant URL); 24h-throttled automatic check at startup + manual "Check for updates" button on the settings page; new-version dialog (version + release notes in a height-capped scroll view) → download (progress display, rejected on sha256 mismatch) → system installer launched via FileProvider (AndroidManifest gains REQUEST_INSTALL_PACKAGES + FileProvider/res/xml/file_paths.xml); GateActivity hosts the automatic check entry, Prefs records the last check time
- Plain-language permission mode names: Confirm every step / Standard auto / Full delegation (dangerous) (server values manual/yolo/auto unchanged, unknown values fall back to raw display); switching to Full delegation shows a ⚠️ warning every time (cancel reverts to the previous mode); on entering a session, fetches the server field of GET /api/v1/meta to identify the backend (kimi/claude/codex, silently defaults to kimi on failure); the yolo description text switches per backend (still asks for sensitive actions / only auto-accepts edits / asks only on failure)
- Approval card shows the full plan text (plan_review): ApprovalItem extended with displayKind/plan/options (parses tool_input_display.kind/plan/options); ExitPlanMode approval uses a height-capped scrollable dialog to display the full plan text (reuses MessageAdapter.decorateCode code decoration, changed to internal) + options single choice + rejection note input; response body supports feedback/selected_label (snake_case, REST spec fields)
- Fixed opening a historical session not scrolling to the bottom (regression introduced by 0.7.3's reading-position preservation): first refresh unconditionally scrolls to the bottom (needInitialScrollToBottom, restoring the old semantics); scrollToBottom hardened — when the last item's bottom edge extends beyond the viewport, scrolls the difference (also fixes the corner case where a long last message only shows its top)
- "/" command completion menu: typing / with no space pops up a filtered ListPopupWindow list (8 commands with Chinese descriptions, item_slash_command.xml); selecting one fills in "/cmd " and places the cursor at the end; automatically dismissed on space/newline/send
- Partial message copy: long-press menu gains "Select to copy" (assistant/tool branches changed from direct copy on long-press to the symmetric menu); in-dialog ScrollView+TextView (setTextIsSelectable) for selecting and copying the original text
- Per-session input drafts: Prefs gains session_drafts (per-session JSON modeled after sessionMode, empty drafts delete the key); saved on onPause, restored on onCreate, cleared on successful send, covering the finish/back-stack/process-kill paths
- sherpa-onnx engine .so changed to on-demand download: gradle packagingOptions.jniLibs.excludes removes 4 .so files (APK 36.6→5.2MB); new NativeEngine (reflection on dexPathList nativeLibraryDirectories + dependency-ordered System.load: onnxruntime→c-api→cxx-api→jni, versioned directory filesDir/native-engine/1.13.7); settings page voice card gains "Download offline engine (~12MB)" (Prefs voice_engine_url, defaults to the GitHub Releases v0.6.1-models asset); when the engine is missing, auto silently falls back to system recognition, onnx prompts to download (same path as missing model)
- New stop button: a red "Stop" shows on the right side of the status bar while a turn is in progress (aligned with macOS/iOS); tapping POSTs :abort (duplicate-tap guarded), automatically hidden when the turn state pushes back; status bar changed to a horizontal container (statusBar)
- bridge: fixed the permission mode mapping being inverted relative to kimi semantics (security bug) — claude_proc changed to auto→bypassPermissions / yolo→acceptEdits, codex_backend changed to auto→never / yolo→on-failure (previously swapped: selecting auto only got acceptEdits/on-failure, while selecting yolo actually granted full delegation)

## 0.7.3 (2026-09-10)
- Image messages (sending pictures): input bar gains an image button, uses the system Photo Picker (ACTION_PICK_IMAGES, no storage permission needed); after selection, uploads on a background thread via POST /api/v1/files (multipart, field name file, Api.uploadFile returns data.id), while a thumbnail preview strip shows above the input area (uploading…/pending + remove button, Toast and clear on upload failure); on send, image blocks ({"type":"image","source":{"kind":"file","file_id":…}}) are interleaved with text blocks and sent along with the prompt (Api.sendPrompt gains an optional imageFileId parameter); image-only messages (no text) can also be sent; tapping send while an image is uploading prompts to wait
- Historical image display: Api.getMessages parses image blocks to extract source.file_id (HistoryMessage gains imageFileId, text filtering logic unchanged, image-only messages preserved); an ImageView embedded in the bubble fetches the binary on a background thread via GET /api/v1/files/{id} when imageFileId is non-empty, decodes and displays it (downsampled to ~1280px long edge, MessageAdapter has a built-in file_id→Bitmap simple memory cache, tag prevents recycled-view misplacement); both user/assistant bubbles supported; local optimistic echo with imageFileId displays immediately, echo confirmation matching adds image comparison
- Image-only messages hide the empty text bubble; the long-press entry is attached to the image (copy/fork-from-here menu unchanged)
- Model selection changed to a server-driven dynamic list: Api gains listModels (GET /api/v1/models → data.items[], with provider/model/display_name/max_context_size) and a shared fallback preset MODEL_PRESETS; the chat page mode bar's model Spinner fetches the server list when entering a session (displays display_name, value is the full model id), silently falls back to 4 presets (k3 / kimi-for-coding / kimi-for-coding-highspeed / k3-256k) on failure/empty; newly configured server models (e.g. amd/deepseek-v4-flash) are directly visible; when the current session's model is not in the list, it is appended and kept
- Settings page global model selection changed from a manual EditText to the same dynamic Spinner (fetched when entering the settings page, falls back to presets on failure/empty; a saved custom value not in the list is appended and kept); saving takes the selected item's full model id
- Fixed the context limit not matching the current model (selecting k3-256k still showed 1000k): limit priority changed to current model's max_context_size > WS maxContextTokens > fallback 1048576; the current model is taken from the session-level mode bar model (falls back to global Prefs.model when empty); the limit refreshes after a model switch/model list arrival
- Fixed auto-refresh pushing the reading position to the bottom: before a loadHistory polling refresh, checks whether the user is near the bottom (2-item tolerance), scrolls to the bottom as before if so; otherwise records the first visible item's message id+offset, and after setAll finds the new index by id to restore the reading position (no drift when prepending pages); streaming new frames (refreshStreamingBubble) likewise only follow when at the bottom
- Hardened keyboard occlusion of the input box: ChatActivity adds an IME insets listener (ViewCompat.setOnApplyWindowInsetsListener), when the IME is visible the chat scrolls to the bottom so the last message + input box are not covered; for third-party IMEs (e.g. MIUI) with abnormal insets where the IME bottom edge overlaps the input bar, manually adds paddingBottom to the input bar (only added when the difference > 0), resets when the IME closes

## 0.7.2 (2026-09-05)
- Historical message pagination, fixing heavy tool sessions "showing only 3 messages": GET /sessions/{id}/messages returns only the latest 100 **raw** messages (including tool role), so after filtering only a few visible bubbles may remain; Api.getMessages gains an optional beforeId parameter (before_id verified working), returns MessagesPage (visible messages + oldest raw message id anchor + raw count, hasMore roughly determined by whether the raw count reaches page_size=100)
- New "Load earlier messages" entry at the top of the chat page (shown only when there may be more, shows "Loading…" while loading): on tap, fetches the previous page with the current oldest raw message id as before_id, prepends and merges the results into the list (earlier timestamps naturally sort first after reconciliation by the existing timeMillis ordering, deduplicated against the latest page by id; queued/active/undelivered bubbles unaffected), prepend refresh keeps the reading position without jumping to the bottom; loading 0 messages prompts "No earlier messages" and hides the entry; when an entire page is filtered out (all tool), prompts that loading can continue

## 0.7.1 (2026-09-05)
- Fixed context usage display: limit takes WS maxContextTokens (only adopted when >0), otherwise falls back to 1048576 (1M, observed server snapshot value); display format fixed as "usage/limit (percentage)" (e.g. "Context 690/1000k (0%)"), always with a percentage; fmtTokens decimal formatting pinned to US locale, fixing display errors like "690.k"
- New "Fork from here": long-press on a user bubble shows a menu (Copy / Fork from here); fork logic: count the number n of user messages after this one already in server history → :fork full clone → :undo {"count":n} on the new session to trim the content after it → jump to the new session (Api gains undoSession, POST /sessions/{id}:undo); status bar shows "Forking…" during the process, Toast on fork failure, on undo failure the new session is kept with a prompt that the content was not trimmed

## 0.7.0 (2026-09-05)
- Context usage display: right side of the mode summary bar adds "Context 23.5k/1000k (2%)"; data source priority ① WS transcript.reset snapshot meta.agent.contextTokens/maxContextTokens ② transcript.ops meta.merge agent.contextTokens ③ GET /sessions/{id} usage.context_tokens/context_limit (observed possibly all 0, only adopted when non-zero, fallback only); refreshed on busy polling (loadHistory) and WS events, hidden when no data
- New "Fork" button in the chat page header: same code path as the /fork command (Api.sessionAction("fork") → jump to the new session)
- New /rename (/title) command: takes all text after the first space as the new title, POST /sessions/{id}/profile top-level title field (not metadata.title); Toast usage hint when empty, Toast + synced top title update on success; /help command list updated accordingly

## 0.6.2 (2026-09-05)
- Fixed / commands matching by first token (/fork with arguments is no longer sent as a normal message)

## 0.6.1 (2026-09-05)
- Offline voice model changed to on-demand download (APK size back from ~213MB to ~35MB): removed the bundled assets/models/ model; SpeechOnnx now loads from filesDir/models/zipformer-bilingual/ (usable only when all four files are present, OnlineRecognizer passed a null assetManager loads by absolute path); settings page voice card gains a "Download offline model" button + editable download URL input (stored in Prefs voice_model_url, defaults to GitHub Releases v0.6.1-models), HttpURLConnection downloads the zip on a background thread to filesDir/tmp then extracts it (java.util.zip, matches the 4-file set by filename, tolerates one directory level inside the zip), progress (downloaded MB/total MB) shown next to the button; ChatActivity integration: in onnx forced mode, tapping the mic when the model is not downloaded prompts "Please download the offline model from the settings page first"; in auto mode, a missing model silently falls back to system recognition
- Slash command support in the input box: intercepts text starting with / before sending, exact match (case-insensitive) of /compact /archive /fork /abort /stop /new /help routes to a server session action POST /api/v1/sessions/{id}:{action} (Api.sessionAction) or a local flow; /archive returns to the session list on success, /fork jumps to the new session on success, /new reuses the list page's session creation flow (workspace selection strategy consistent with SessionsActivity), /help shows a dialog listing commands; other text starting with / is sent as a normal prompt (consistent with the official behavior); commands all execute on background threads, results fed back via Toast/status bar

## 0.6.0 (2026-09-05)
- New self-hosted offline speech recognition engine sherpa-onnx (1.13.7 local AAR, Apache-2.0): bundled Chinese-English bilingual streaming model zipformer-bilingual-2023-02-20 (int8 quantized, ~190MB assets, not committed to git); AudioRecord 16kHz mono capture + streaming recognition, partial results shown in the status bar, final result appended to the input box on endpoint/stop; tapping the mic again during recognition ends it and flushes the trailing results
- Settings page gains a "Voice engine" option (auto/onnx/system, default auto): under auto, offline is preferred when the model exists, automatically falls back to the system SpeechRecognizer when the model is missing or initialization fails (old path kept); onnx forces offline, system forces system
- Recording permission reuses the existing RECORD_AUDIO request logic

## 0.5.2 (2026-09-02)
- Q&A card/dialog content area height-capped and scrollable: the submit button at the bottom is always reachable when there are many questions and options (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the sending side: system-injection filter supplemented with the <notification prefix (synced across all three platforms)

## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the sending side: system-injection filter supplemented with the <notification prefix (synced across all three platforms)


## 0.5.1 (2026-09-02)
- Fixed background task notifications (<notification ...>) being displayed as user messages on the sending side: system-injection filter supplemented with the <notification prefix (synced across all three platforms)
## 0.5.0 (2026-09-02)
- New Q&A support (pending_interaction="question" scenario): when the server sends AskUserQuestion, ChatActivity polls GET /questions?status=pending every 5s (sharing the 5s polling timer with approvals), shows a dialog when there is a pending one: each question renders a RadioGroup single choice (label — description), appends "Other (custom answer)" + text input when allow_other; submits {"kind":"single","option_id":...} / {"kind":"other","text":...}; the "Skip" button sends {"kind":"skipped"}; the dialog does not close until all questions are answered (manually takes over submit button validation); multiple pending items handled one by one (same as approvals)

## 0.4.9 (2026-08-21)
- Settings page footer shows the current version number (versionName + versionCode, from BuildConfig)

## 0.4.8 (2026-08-20)
- Fixed conversation order disorder: loadHistory reconciliation previously appended local echo/queued/active bubbles unconditionally at the end of the list, causing disorder when they were earlier than the latest history entries; changed to sort ascending by timeMillis (history uses created_at, queued/active bubbles use the queue API's created_at, local echo uses send time, those without timestamps go last) before setAll
- Api.listQueuedPrompts now parses created_at as well: new QueuedPrompt(text, createdAt), PromptQueue.queued/active both changed to QueuedPrompt
- Fallback reconciliation even when idle: history polling changed to always-on in the foreground (busy 15s / idle 60s, stopped on onPause to prevent stacking); badge states (queued/active/undelivered) of idle sessions no longer freeze once WS is stable
- New reconciliation diagnostic log (tag "Reconcile"): history count, queue/active summary, per-local-echo determination (history-confirmed/server queue/active/undelivered/POST in flight)

## 0.4.7 (2026-08-20)
- Fixed "entering a session mid-turn shows no progress" (v0.37.2 server: WS clients that subscribe mid-turn do not receive that turn's transcript.ops, including streaming content and the turn.upsert completion event):
  - transcript.reset branch parses payload.snapshot.meta.agent.phase as the real-time phase and calls back onPhase (among multiple resets from multiple agents, takes main: agent_id=="main" or the one with meta), immediately shows "Working/Thinking" on entering a session (new tool_call phase display)
  - polls loadHistory every 15s during busy (started when turnActive, stopped on onPause to prevent stacked timers); when polling detects busy gone (data.active empty and queue cleared) → immediately unified refresh; onWorkChanged(false)/phase ended also refresh as fallback. Replies become visible within 15s after an in-progress turn completes, no longer depending on the unreachable turn.upsert
- Active is no longer called "queued": GET /prompts?status=queued's data.active is the currently executing prompt (v0.37.2, not in queued[]); matched with local echo → marked with a small "Active" label (ChatMsg gains active field), renders an active-0 server bubble when re-entering a session without echo; messages in active are not marked "undelivered"
- Api.listQueuedPrompts return type changed to PromptQueue(queued, active)

## 0.4.6 (2026-08-20)
- Fixed stale "Running" badge in the session list: automatic loadAll() every 30s while resumed (stopped on onPause; loadAll is read-only, does not interrupt pull-to-refresh/input)
- Fallback for queued messages silently dropped by the server (upstream bug #3127: queued prompts silently dropped under phantom busy): local echoes that are neither in history nor in the server queue and have existed for over 60s → marked "Undelivered (dropped by server)" (red warning, no longer shows "Queued"); POST in flight (<60s) kept as normal
- ChatMsg gains undelivered field; queued-* bubble behavior unchanged (rebuilt from the server queue each round, naturally disappears when absent from both queue and history)

## 0.4.4 (2026-08-20)
- Fixed "queued messages disappear after re-entering a session/killing the app": the server queue is the source of truth, loadHistory also fetches GET /prompts?status=queued, messages in the queue render as user bubbles with a small "Queued" label (deduplicated against local echo by same text, only one shown)
- pendingLocal reconciliation upgrade: confirmed in history → removed; present in the server queue → rendered by the server queue bubble instead; in neither history nor queue (POST in flight) → kept
- Falls back to the old behavior (keep all unconfirmed echoes) when the queue API fetch fails, without blocking history loading
- Local echo immediately marked "Queued" when POST returns queued (MessageAdapter gains markQueued; ChatMsg gains queued field)

## 0.4.3 (2026-08-20)
- Fixed "sent messages disappear": when busy, the server returns queued and the message temporarily does not enter history, loadHistory's setAll would wipe the optimistic echo → maintain pendingLocal, unconfirmed echoes appended at the end of history, kept after reconnect/refresh; automatically confirmed and removed when a user message with the same text appears in history
- sendPrompt returns the server status: status bar shows "Queued, waiting for the current task to finish…" when queued
- Optimistic echo retracted on send failure (MessageAdapter gains removeById)
- New tool activity stream: parses WS frame.upsert kind=tool frames (display.summary ?: inputText ?: input, truncated to 80 chars), inserts small gray temporary entries into the message list (running 🔧 / done ✓), status bar shows "Working: tool name"; naturally cleared with loadHistory after the turn ends

## 0.4.2 (2026-08-15)
- Fixed "sessions lost": listSessions no longer carries busy=false, running/pending-approval sessions are visible again, "Running" badge shown next to the title
- New tool approval: polls pending approvals every 5 seconds after entering a session (foreground only), dialog shows tool name + summary, supports approve/deny, multiple items handled one by one
- Default workspace changed to "last selected → workspace with the largest session_count → the first one" (old logic hardcoded a Linux path, wrongly landing on Downloads on Mac/iOS hosts)
- WorkspaceItem gains session_count parsing

## 0.4.1 (2026-08-15)
- Mode state mechanism aligned with the official web UI: persisted locally per session (SharedPreferences), prompts carry top-level mode fields when sending messages (plan_mode/swarm_mode/permission_mode/model)
- Background: server v0.35.0 GET /profile does not return the real agent_config, echo is not trustworthy

## 0.4 (2026-08-15)
- Session mode bar: plan mode/Swarm toggle, permission mode (Manual/Auto/YOLO), model switch, goal mode (set/pause/resume/cancel)
- Fix: cleartext HTTP whitelist was hardcoded to a single host address, preventing adding other hosts → allowed globally (private tailnet for personal use)
- Fix: gate page gains a persistent "Server settings" entry to prevent lockout when unreachable
- Fix: Tailscale launch (Android 11+ package visibility queries declaration) + system VPN settings fallback

## 0.3.2 (2026-08-15)
- Fixed horizontal truncation of user bubble text (layout constraint)
- Fixed the last message being covered by the input box (RecyclerView padding + scroll timing)
- Fixed reversed history message order (API returns newest first, needs reversing)

## 0.3 (2026-08-15)
- Filter system-injected phantom user messages (blocks starting with <system-reminder> / <cron-fire)

## 0.2 (2026-08-14)
- Material Design 3 polish: new icon (blue-purple gradient + bubble K), day/night dual themes, splash, chat bubble redesign, long-press copy, code block rendering

## 0.1 (2026-08-14)
- Initial release: Tailscale gating, session list + workspace switching, WS streaming chat (transcript.ops), voice input (SpeechRecognizer zh-CN), multi-host profiles

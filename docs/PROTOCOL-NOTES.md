# Kimi Code Server Protocol Field Notes

> This document records the **observed behavior** of the Kimi Code local server (`kimi web`, REST + WebSocket) discovered while developing the three Kimi Mobile clients. All the pitfalls missing from the official OpenAPI/AsyncAPI docs (see `kimi-openapi.json` / `kimi-asyncapi.json`) are collected here. Testing baseline: server 0.35.0 / 0.37.2.

## 1. Session List

`GET /api/v1/sessions`

- `page_size` is capped at **100**; exceeding it returns 40001
- The response contains only `items` + `has_more`; **there is no total**
- Supports `busy=true|false` filtering. **Do not pass `busy=false` in client list queries** — sessions that are running or stuck on approval get filtered out, which looks like "sessions disappearing" (we hit this)
- `busy` indicates whether the session has an active turn; `pending_interaction` is one of `none|approval|question`

## 2. Message History

`GET /api/v1/sessions/{id}/messages`

- Returns **newest first**; clients must reverse into chronological order
- **Queued prompts do not appear in history** (see next section)
- System-injected user messages must be filtered or they show up as "sent by the user": text blocks starting with `<system-reminder>`, `<cron-fire`, or `<notification` (the official Web UI hides them too)

## 3. Sending & Queueing (the biggest pitfall)

`POST /api/v1/sessions/{id}/prompts`

- The top level must include `model`; mode fields (`plan_mode/swarm_mode/permission_mode/thinking/goal_*`) ride along at the top level of each prompt (**only include non-default fields**, matching the official Web UI's localStorage mechanism)
- Response `data.status`:
  - `running`: executes immediately
  - **`queued`: the session is busy and the message is queued** — at this point the message **does not appear in `GET /messages`**; it only shows up once its turn comes
- Queue query: `GET /api/v1/sessions/{id}/prompts?status=queued` → `data: {active, queued[]}`
  - `active` is the **currently executing prompt** (since 0.37.2 it is **not** inside `queued[]`)
  - Each entry in `queued[]` contains `prompt_id/content[].text/created_at`
- Correct client behavior: after sending, if `status=queued`, show "queued"; treat the **server-side queue as the source of truth** when reconciling local optimistic echoes — already in history → confirmed, present in queue → queued, matching active → executing, in none of these and older than 60s → possibly dropped, mark "undelivered"

### Phantom-busy / zombie active (severe)

Observed multiple times in testing: the session's active slot gets permanently occupied by a **prompt that finished or died abnormally long ago** (`status: running` for over 24 hours), after which every new prompt queues forever and never executes. `GET /sessions/{id}` may show `busy:false` at this point (the slot state and the busy flag are out of sync).

- **Antidote**: `POST /api/v1/sessions/{id}:abort` (note the **colon-suffix** syntax, not `/abort`) — kills the current active prompt and automatically promotes the next queued one; repeated calls drain the entire backlog one by one
- We reported this upstream: [MoonshotAI/kimi-code#3127](https://github.com/MoonshotAI/kimi-code/issues/3127)
- The trigger is suspected to be related to "running multiple server processes on the same data directory" or "a turn being interrupted abnormally"

### Session action suffixes

`POST /api/v1/sessions/{id}:{action}`, e.g. `:abort`. Path-style forms like `/abort` or `/interrupt` all return 40001 "unsupported action".

## 4. Approvals

- Query: `GET /api/v1/sessions/{id}/approvals?status=pending` → `items[]` (`approval_id/tool_name/action/tool_input_display.summary/created_at/expires_at`)
- Respond: `POST /api/v1/sessions/{id}/approvals/{approval_id}`, body `{"decision":"approved"|"rejected"|"cancelled"}`
  - `approved`/`rejected`: allow/deny the tool call; the agent continues
  - **`cancelled`: aborts the entire turn**
- The server does not proactively push approvals over WS (or does so unreliably); a 5s client-side poll is sufficient

## 5. Questions

When the agent invokes AskUserQuestion, `pending_interaction=question`.

- Query: `GET /api/v1/sessions/{id}/questions?status=pending` → `items[]`, each item:
  ```json
  {
    "question_id": "question_xxx",
    "questions": [{
      "id": "q_0", "question": "...", "header": "...",
      "options": [{"id": "opt_0_0", "label": "...", "description": "..."}],
      "allow_other": true
    }]
  }
  ```
- Answer: `POST /api/v1/sessions/{sid}/questions/{question_id}`, body is a record (verified against the zod schema):
  ```json
  {
    "answers": {
      "q_0": {"kind": "single", "option_id": "opt_0_0"},
      "q_1": {"kind": "other", "text": "free text"},
      "q_2": {"kind": "skipped"}
    }
  }
  ```
  All `kind` values: `single` (option_id), `multi` (option_ids), `other` (text), `multi_with_other`, `skipped`

## 6. Session mode profile (a 0.35.0 pitfall)

- `GET /sessions/{id}/profile`'s `toWireSession` **hardcodes** `agent_config: {model: ""}` — it **never echoes the real mode state**
- Correct mechanism (same as the official Web UI): the client persists modes locally per sessionId and attaches mode fields at the top level of every `POST /prompts`
- Patches via `POST /profile` **do take real effect** on a running agent (`applyAgentConfig`)
- Goal control: `POST /profile`, body `{"agent_config":{"goal_control":"pause|resume|cancel"}}`; returns 40914 when there is no goal

## 7. WebSocket

Endpoint `/api/v1/ws`; the handshake carries `Authorization: Bearer <token>`.

### Handshake & subscription

```
→ {"type":"client_hello","id":"h-x","payload":{"client_id":"..."}}
← {"type":"server_hello","payload":{"protocol_version":2,"heartbeat_ms":10000,...}}
← {"type":"ack","id":"h-x","code":0}
→ {"type":"subscribe_v2","id":"s-x","payload":{"session_id":"...","transcript":{"*":"delta"}}}
← {"type":"transcript.reset", ...} (possibly multiple, one per agent)
← {"type":"ack","id":"s-x"}
```

### Heartbeat

- The server sends a JSON `{"type":"ping","payload":{"nonce":...}}` every 10s; the client must reply with `{"type":"pong","payload":{"nonce":...}}`
- **0.34.0 observed not sending heartbeats** (0.35.0/0.37.2 are fine). Clients cannot rely solely on "disconnect after N seconds of silence" for liveness — our fix: after 15s of idleness, proactively send a protocol-level WS ping (0x9); only reconnect after 35s with no frames at all

### transcript.ops event stream

`{"type":"transcript.ops","payload":{"agent_id":"main","ops":[...]}}`; op types:

- `frame.upsert`: a new content frame. `frame.kind`:
  - `text` (only `role=assistant` is actual content; user frames may contain system injections and must be filtered)
  - `thinking` (reasoning process)
  - **`tool` (tool call activity)**: `name/state(running|done)/input/inputText/display`
- `append`: appends text to an existing frame (`target.frameId` + `offset` + `text`)
- `meta.merge`: agent phase `phase.kind` (`running/streaming/ended/interrupted`, `stream=thinking|text`)
- `turn.upsert`: turn status (`running/completed/failed/cancelled`)

### Key pitfall: late subscribers miss the in-flight turn (0.37.2)

A WS subscriber **only receives transcript.ops for turns that start after it subscribed**. Open a session that is already running: no streaming content at all, not even the turn.upsert completion event. However, `transcript.reset`'s `payload.snapshot.meta.agent.phase` contains the real-time phase (e.g. `{"kind":"tool_call","turnId":N,...}`).

Client countermeasures:
- When entering a session, parse the reset snapshot's phase and immediately show "working"
- While busy, **poll `GET /messages` every 15s** (including queue reconciliation); the reply becomes visible within 15s of turn completion, without relying on the undeliverable turn.upsert

### Multiple instances

Multiple servers can run on the same `~/.kimi-code` (registered under `~/.kimi-code/server/instances/`); they share the on-disk session history but their **in-memory queues and agent states are independent**. Two processes running the same session cause split-brain (queues invisible to each other, zombie active slots). Upstream adds no locking — **make sure only one server runs per data directory**.

## 8. Miscellaneous

- `GET /api/v1/meta`: `server_version`, `capabilities`, `server_id` — when troubleshooting, first confirm which process/version you are talking to
- `page_size` cap is 100; error responses look like `{"code":40001,"msg":...,"details":[{"path","message"}]}` (the zod details are directly usable for debugging)
- Token: `~/.kimi-code/server.token` (shared across the home directory; rotate with `kimi web rotate-token`)

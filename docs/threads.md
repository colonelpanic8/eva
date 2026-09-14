# Threads: sessions as front-ends, work that outlives the call

Design for the thread model. Reviewed by a second model on 2026-09-14; the
decisions below fold that review in. `docs/implementation.md` says what is
actually built.

## Model

- A **thread** is the durable unit: an ordered, attributed list of items
  (user messages, assistant messages, action calls with arguments, session
  notices) plus the turns that group them. Threads persist across process
  restarts in the existing journal database, so a claim, its turn linkage,
  and the turn's side-effect reservation commit atomically.
- A **turn task** owns one user request from acceptance through every tool
  call and follow-up generation until an answer is recorded. It is created
  when input is accepted (typed submit, or a spoken response starting), not at
  the first tool call, so an ordinary answer survives hang-up too. At most one
  task runs per thread. It lives in a thread-owned scope, never as a child of
  a session.
- An **attachment** is the transient interactive front-end: a voice call
  (microphone, playback, the realtime socket) or a text connection. Exactly one
  attachment is live at a time. Ending it never finalizes a task; it only
  removes the leg the task would have answered through.
- A **leg** is the provider session a task delivers tool results to and
  receives model output from. A task starts on its attachment's leg. When the
  attachment ends with the task unfinished, the task **re-homes** onto a
  background Responses leg seeded with the thread's history. Background legs
  may coexist with the one interactive attachment.
- Assistant-mode launches (`Launch.HANDS_FREE`, `SECURE_HANDS_FREE`, the
  `VoiceInteractionSession`) start a **new thread** unless a voice attachment
  is already live. Any thread can be **resumed** by opening a text or voice
  attachment on it from the drawer.

## Durable types (`conversation/Thread.kt`)

```kotlin
data class Thread(val id: String, val title: String, val createdAtMillis: Long, val updatedAtMillis: Long)

enum class TurnStatus { OPEN, ANSWERED, INTERRUPTED, FAILED }

data class Turn(
    val id: String, val threadId: String, val request: String,
    val status: TurnStatus, val createdAtMillis: Long,
    /** The one side-effecting call this turn has claimed, persisted so a fresh leg cannot claim another. */
    val sideEffectCallId: String? = null,
)

sealed interface ThreadItem {
    val id: String; val threadId: String; val turnId: String?; val createdAtMillis: Long
    data class UserMessage(…, val text: String, val spoken: Boolean) : ThreadItem
    data class AssistantMessage(…, val text: String, val spoken: Boolean, val truncated: Boolean) : ThreadItem
    /** Result lives in the invocation journal under [callId]; it is joined at read time, never copied. */
    data class ActionCall(…, val callId: String, val capabilityId: String, val title: String, val arguments: Map<String, String>) : ThreadItem
    data class Notice(…, val kind: NoticeKind, val text: String) : ThreadItem
}

enum class NoticeKind { SESSION_STARTED, SESSION_ENDED, REHOMED, INTERRUPTED }
```

`InvocationRecord` gains `threadId: String?` and `turnId: String?` (null for
rows written before schema v3). `ToolProposal` carries the same two fields so
the dispatcher journals them with the claim.

## Store (`conversation/ConversationStore.kt`)

```kotlin
interface ConversationStore {
    val changes: Flow<String>                                   // thread id whose items or turns changed
    suspend fun createThread(title: String): Thread
    suspend fun threads(): List<Thread>                         // newest updated first
    suspend fun thread(id: String): Thread?
    suspend fun retitle(id: String, title: String)
    suspend fun items(threadId: String, limit: Int = 200): List<ThreadItem>   // oldest first, the last `limit`
    suspend fun turns(threadId: String): List<Turn>
    suspend fun openTurn(threadId: String, request: String, id: String): Turn
    suspend fun closeTurn(turnId: String, status: TurnStatus)
    /** True if this call now holds the turn's single side-effect claim, or already did. */
    suspend fun reserveSideEffect(turnId: String, callId: String): Boolean
    suspend fun append(item: ThreadItem)
    /** OPEN turns left by a dead process become INTERRUPTED, once, at application start. */
    suspend fun recoverInterrupted(): List<Turn>
}
```

Two implementations: `SqliteConversationStore` sharing `JournalDatabase` with
`SqliteInvocationRepository` (schema v3: tables `threads`, `turns`, `items`;
`invocations` gains `thread_id`, `turn_id`), and `MemoryConversationStore` for
JVM tests. The old global 100-row invocation history query is replaced by
thread-scoped reads.

## Provider legs

`SessionOpenRequest` gains:

```kotlin
val history: List<HistoryItem> = emptyList()      // seed for a resumed or re-homed leg
val continuation: Continuation? = null           // present only on a re-homed leg
data class Continuation(val turnId: String)      // the leg must produce this turn's next generation without new user input
```

`HistoryItem` is the provider-facing projection of `ThreadItem` with results
joined: `User(text)`, `Assistant(text)`, `ActionEvidence(title, arguments,
status, message)`, `Note(text)`. History is bounded (last N items plus a
one-line summary of what was dropped) by the controller, not the provider.

- **Responses (`OpenAiResponsesSession`)**: history becomes input items ahead of
  the first request. User and assistant text are ordinary messages; action
  evidence and notes are `developer`-role messages that say plainly they are
  EVA's receipts, not the user speaking. On the stored (API-key) path the seed
  is sent once and `previous_response_id` carries on from there. With a
  `continuation`, `requestResponse(ResponseRequest(turnId))` is legal without
  a prior `submit`: the session posts the seeded history and asks for the next
  generation. A re-homed leg never resends the original request as fresh input.
- **Realtime (`OpenAiRealtimeSession`)**: history is inserted with
  `conversation.item.create` (user/assistant messages as their roles, evidence
  and notes as `system` items) before `Connected` is emitted, and the
  microphone stays muted until every item is acknowledged. Instructions keep
  behaviour only.
- Tool results are connection-local: a `CorrelatedToolResult` from an ended leg
  is never delivered to another. The re-homed leg sees the result as
  `ActionEvidence` in its seed instead.

## Turn budget

`CapabilityDefinition` gains `readOnly: Boolean = false`. Read-only today:
contacts search, conversations search/read, device state get/metadata, media
now-playing, UI observe. Per turn:

- side-effecting calls: one, reserved through `ConversationStore.reserveSideEffect`
  so a fresh leg (reconnect or re-home) cannot claim a second;
- read-only calls: up to `READ_ONLY_CALLS_PER_TURN = 8`, counted in the task.

Rejections carry the same messages as today. The broker path enforces its own
single-action rule independently and is unchanged here.

## Controller (`conversation/ThreadController.kt`, replaces `ProviderSessionController`)

State exposed to the UI:

```kotlin
data class ConversationState(              // the shown thread
    val threadId: String?, val entries: List<ConversationEntry>, val working: Boolean, …existing fields…
)
data class ThreadSummary(val id: String, val title: String, val updatedAtMillis: Long, val working: Boolean)
val threads: StateFlow<List<ThreadSummary>>
```

Operations: `newThread()`, `showThread(id)`, `connect(link)`, `connectVoice(link)`
(both attach to the shown thread; a voice attachment on a thread with history
seeds it), `disconnect()` (attachment only), `stopTask()` (cancels the shown
thread's task and records `INTERRUPTED`), `submit(text)` (rejected while the
thread's task is running: "EVA is still working on the last request.").

Guards: the existing `attempt`/epoch guards protect attachment-owned state
(media, provider status) exactly as now. Task-owned state is keyed by
`(threadId, turnId)` and a **leg generation**: provider events are accepted
only from the task's current leg; journal receipts are applied regardless of
attachment. `disconnect()` no longer calls `finishInput` or cancels action jobs.

Re-homing rule: when the attachment ends and the thread's task is unfinished,
the task invalidates its leg, waits for in-flight dispatches to settle
(journal is authoritative), then opens a background Responses leg with
`continuation = Continuation(turnId)` seeded from the thread. If the task had
already produced its answer, nothing happens. One transfer per turn: if the
background leg fails, the turn is `FAILED` with a notice.

Voice: `ResponseStarted` opens a turn; the user transcript that completes for
that response is stored as the turn's request. Realtime completion is
correlated by response id, and generation completion is distinct from task
completion (a fast tool result must not clear the active input early).

## Android lifetime

`VoiceSessionService` keeps its microphone/media-playback types and its
"voice attachment live" trigger. A new `TurnWorkService` of type
`shortService` (API 34+; untyped below) runs while any thread has a task and no
voice attachment is live. Its `onTimeout` interrupts running tasks and records
`INTERRUPTED`. A task that finishes with no attachment posts a notification on
channel `eva.work` with the answer's first line; opening it shows the thread.

## UI

- `ConversationEntry` stays the UI projection. Its grouping helper is
  `EntryGroup`/`groups()` (renamed from `ConversationThread`/`threads()` to
  avoid colliding with durable threads).
- Drawer lists threads (title, relative time, working indicator) with
  "New thread". The shown thread's screen offers Resume (text/voice) when no
  attachment is live, Disconnect for the attachment, and Stop while a task runs.
- Session notices render as dividers; REHOMED and INTERRUPTED as labelled
  dividers.

## Slices

1. Durable threads and detached tasks: types, store, schema v3, `ThreadController`
   with turn tasks that survive `disconnect()`, re-homing onto Responses,
   read-only budget, work service, notification, drawer thread list, new
   thread on assistant launch, text resume.
2. Voice resume: realtime seeding with acknowledgement gating; transcript
   identity fixes.
3. Queueing input behind a running task; barge-in policy.

## Verification

JVM: store contract tests (shared between Sqlite via Robolectric and Memory),
controller tests for: task survives disconnect and receipt lands; re-home
produces an answer through a fake Responses leg; second side-effect rejected
across re-home; read-only budget; late-open isolation preserved; result before
`response.done` ordering. Device: schema v2→v3 migration keeps receipts;
`TurnWorkService` starts and stops with tasks.

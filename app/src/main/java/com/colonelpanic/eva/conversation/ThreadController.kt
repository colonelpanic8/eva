package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationPersistenceException
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.ProposalRejectedException
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.Continuation
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Threads are durable; a voice call or text connection is an attachment that comes and goes.
 * The work of a turn belongs to its [TurnTask], which is not a child of the attachment: when
 * the call ends with the turn unfinished, the task re-homes onto a background text leg and
 * finishes there. See docs/threads.md. Calls and [scope] are confined to the UI dispatcher.
 */
class ThreadController(
    private val registry: CapabilityRegistry,
    private val dispatcher: CapabilityDispatcher,
    private val repository: InvocationRepository,
    private val store: ConversationStore,
    private val scope: CoroutineScope,
    private val providerFactory: (String) -> ConversationProvider,
    private val mediaFactory: (() -> RealtimeMediaSession)? = null,
    private val voiceProviderFactory: (suspend (String, RealtimeMediaSession) -> ConversationProvider)? = null,
    /** The leg a turn continues on once its call has ended; a text provider on the phone's own account. */
    private val backgroundProviderFactory: () -> ConversationProvider = { providerFactory("") },
    private val voiceLookupRetries: () -> Int = { 5 },
    private val voiceKeywords: suspend () -> List<String> = { emptyList() },
    /** Called with the answer a turn produced while nothing was attached to its thread. */
    private val onBackgroundAnswer: (BackgroundAnswer) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class BackgroundAnswer(
        val threadId: String,
        val title: String,
        val answer: String,
    )

    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private val mutableThreads = MutableStateFlow<List<ThreadSummary>>(emptyList())
    val threads = mutableThreads.asStateFlow()
    private val mutableWorking = MutableStateFlow<Set<String>>(emptySet())

    /** Threads with a running turn task, attached or not. */
    val working = mutableWorking.asStateFlow()

    private var shownThreadId: String? = null
    private val tasks = mutableMapOf<String, TurnTask>()

    private var media: RealtimeMediaSession? = null
    private var connectionJob: Job? = null
    private var session: ConversationSession? = null
    private var attempt = 0
    private var attachedThreadId: String? = null
    private var ending = false
    private var assistantSpeaking = false

    private val definitions = registry.catalog.associateBy { it.id }
    private val phoneTools = registry.catalog.map { ProviderToolDefinition(it.id, it.title, it.description, it.inputSchema) }
    private val typedCatalog = catalogOf(phoneTools)

    // Only a spoken session is something the model can hang up.
    private val voiceCatalog = catalogOf(phoneTools + END_CONVERSATION)

    init {
        scope.launch {
            try {
                repository.recoverInterrupted()
                store.recoverInterrupted().forEach { turn ->
                    store.append(notice(turn.threadId, turn.id, NoticeKind.INTERRUPTED, "EVA closed before this request finished."))
                }
                shownThreadId = store.threads().firstOrNull()?.id
                refresh()
                mutableState.update { it.copy(isLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, errorMessage = SessionController.STORAGE_ERROR) }
            }
        }
        scope.launch { store.changes.collect { refresh() } }
    }

    // ---- threads ----

    /** Starts an empty thread and shows it. The next attachment or request lands there. */
    fun newThread() {
        scope.launch {
            val thread = store.createThread(UNTITLED)
            shownThreadId = thread.id
            refresh()
        }
    }

    fun showThread(id: String) {
        shownThreadId = id
        scope.launch { refresh() }
    }

    private suspend fun shownOrNewThread(): String = shownThreadId ?: store.createThread(UNTITLED).id.also { shownThreadId = it }

    private suspend fun refresh() {
        val id = shownThreadId
        val summaries =
            store.threads().map { ThreadSummary(it.id, it.title, it.updatedAtMillis, activeTask(it.id) != null) }
        mutableThreads.value = summaries
        mutableWorking.value =
            tasks.values
                .filter { it.active }
                .map { it.threadId }
                .toSet()
        if (id == null) {
            mutableState.update { it.copy(threadId = null, entries = emptyList(), working = false) }
            return
        }
        val items = store.items(id)
        val entries = projectEntries(store.turns(id), items, receipts(items))
        mutableState.update { it.copy(threadId = id, entries = entries, working = activeTask(id) != null) }
    }

    private suspend fun receipts(items: List<ThreadItem>): Map<String, InvocationRecord> {
        val wanted = items.filterIsInstance<ThreadItem.ActionCall>().map { it.callId }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        return repository.history().filter { it.callId in wanted }.associateBy { it.callId }
    }

    private fun activeTask(threadId: String): TurnTask? = tasks.values.firstOrNull { it.threadId == threadId && it.active }

    private fun notice(
        threadId: String,
        turnId: String?,
        kind: NoticeKind,
        text: String,
    ) = ThreadItem.Notice(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), kind, text)

    // ---- attachment ----

    fun connect(link: String) = connectSession(link, voice = false)

    fun connectVoice(
        link: String,
        newThread: Boolean = false,
    ) = connectSession(link, voice = true, newThread = newThread)

    fun toggleMicrophone() {
        media?.let { it.setMicrophoneMuted(!it.controls.value.microphoneMuted) }
    }

    fun togglePlayback() {
        media?.let { it.setPlaybackMuted(!it.controls.value.playbackMuted) }
    }

    private fun connectSession(
        link: String,
        voice: Boolean,
        newThread: Boolean = false,
    ) {
        if (state.value.isLoading || state.value.errorMessage != null) return
        disconnect()
        val thisAttempt = attempt
        mutableState.update { it.copy(providerStatus = ProviderStatus.CONNECTING, providerMessage = null, voiceMode = voice) }
        connectionJob =
            scope.launch {
                var openedSession: ConversationSession? = null
                var threadId: String? = null
                try {
                    threadId = if (newThread) store.createThread(UNTITLED).id.also { shownThreadId = it } else shownOrNewThread()
                    attachedThreadId = threadId
                    refresh()
                    val connectionCatalog = if (voice) voiceCatalog else typedCatalog
                    val provider =
                        if (!voice) {
                            providerFactory(link)
                        } else {
                            val audio = checkNotNull(mediaFactory).invoke()
                            media = audio
                            launch {
                                audio.controls.collect { controls ->
                                    if (thisAttempt == attempt) mutableState.update { it.copy(mediaControls = controls) }
                                }
                            }
                            launch {
                                audio.state.collect { audioState ->
                                    if (thisAttempt == attempt) {
                                        mutableState.update { it.copy(mediaState = audioState) }
                                        if (audioState is RealtimeMediaState.Failed) {
                                            mutableState.update { it.copy(providerMessage = audioState.reason.message) }
                                            disconnect()
                                        }
                                    }
                                }
                            }
                            checkNotNull(voiceProviderFactory).invoke(link, audio)
                        }
                    val items = store.items(threadId)
                    val opened =
                        provider.open(
                            SessionOpenRequest(
                                instructions(voice),
                                connectionCatalog,
                                // Captions only; a typed session has no audio to transcribe.
                                if (voice) voiceKeywords() else emptyList(),
                                history = projectHistory(items, receipts(items)),
                            ),
                        )
                    openedSession = opened
                    currentCoroutineContext().ensureActive()
                    session = opened
                    opened.events.takeWhile { it != ProviderEvent.Closed }.collect { event ->
                        if (thisAttempt != attempt) return@collect
                        onAttachedEvent(event, opened, threadId, voice)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (thisAttempt == attempt) {
                        mutableState.update { it.copy(providerMessage = error.message?.take(300) ?: "Provider connection failed.") }
                    }
                } finally {
                    currentCoroutineContext().cancelChildren()
                    if (openedSession != null && threadId != null) {
                        withContext(NonCancellable) { store.append(notice(threadId, null, NoticeKind.SESSION_ENDED, "Session ended")) }
                    }
                    if (thisAttempt == attempt) {
                        session = null
                        media?.close()
                        media = null
                        attachedThreadId = null
                        mutableState.update {
                            it.copy(
                                providerStatus = ProviderStatus.DISCONNECTED,
                                providerModel = null,
                                voiceMode = false,
                                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
                            )
                        }
                    }
                    threadId?.let { detach(it, openedSession) }
                    withContext(NonCancellable) {
                        try {
                            openedSession?.close()
                        } catch (_: Exception) {
                            if (thisAttempt == attempt) {
                                mutableState.update {
                                    it.copy(providerMessage = it.providerMessage ?: "Provider cleanup failed. Reconnect to try again.")
                                }
                            }
                        }
                    }
                }
            }
    }

    private suspend fun onAttachedEvent(
        event: ProviderEvent,
        opened: ConversationSession,
        threadId: String,
        voice: Boolean,
    ) {
        when (event) {
            is ProviderEvent.Connected -> {
                val model =
                    listOfNotNull(event.model, event.backendModel)
                        .distinct()
                        .joinToString(" · ")
                        .ifBlank { null }
                mutableState.update {
                    it.copy(providerStatus = ProviderStatus.CONNECTED, providerMessage = null, providerModel = model)
                }
                val label = listOfNotNull(if (voice) "Voice session" else "Text session", model).joinToString(" · ")
                store.append(notice(threadId, null, NoticeKind.SESSION_STARTED, label))
            }

            is ProviderEvent.Account -> {
                mutableState.update { it.copy(providerLabel = event.label) }
            }

            is ProviderEvent.ResponseStarted -> {
                if (tasks[event.inputId] == null && voice) {
                    // A spoken turn has no typed input; the response is the turn, and its transcript
                    // may still be in flight. Nothing else may own this thread's next answer.
                    activeTask(threadId)?.let { previous -> if (previous.dispatches.none { it.isActive }) previous.complete() }
                    startTask(threadId, event.inputId, "", opened, spoken = true)
                }
            }

            is ProviderEvent.Transcript -> {
                val task = activeTask(threadId)
                if (event.role == "user") {
                    task?.request = event.text
                    store.append(
                        ThreadItem.UserMessage(
                            UUID.randomUUID().toString(),
                            threadId,
                            task?.turnId,
                            nowMillis(),
                            event.text,
                            spoken = true,
                        ),
                    )
                    titleFrom(threadId, event.text)
                } else if (event.role == "assistant") {
                    store.append(
                        ThreadItem.AssistantMessage(
                            UUID.randomUUID().toString(),
                            threadId,
                            task?.turnId,
                            nowMillis(),
                            event.text,
                            spoken = true,
                        ),
                    )
                }
            }

            is ProviderEvent.ToolCallReady -> {
                if (voice && event.capabilityId == END_CONVERSATION.capabilityId) {
                    // Nothing runs on the phone and no result is returned, so the model is not
                    // prompted to speak again. Its goodbye plays out first.
                    ending = true
                    scope.launch {
                        if (assistantSpeaking) delay(END_SPEECH_LIMIT_MILLIS)
                        hangUp()
                    }
                } else {
                    tasks[event.call.inputId]?.takeIf { it.leg === opened }?.dispatch(event)
                }
            }

            is ProviderEvent.AssistantSpeaking -> {
                assistantSpeaking = event.speaking
                if (ending && !event.speaking) {
                    scope.launch {
                        // The phone still holds a little audio after the server drains.
                        delay(PLAYOUT_TAIL_MILLIS)
                        hangUp()
                    }
                }
            }

            is ProviderEvent.AssistantText -> {
                tasks[event.inputId]?.takeIf { it.leg === opened }?.answer(event.text, event.truncated, voice)
            }

            is ProviderEvent.ResponseEnded -> {
                tasks[event.inputId]?.takeIf { it.leg === opened }?.generationEnded(event.status)
            }

            is ProviderEvent.Failure -> {
                throw IllegalStateException(event.message)
            }

            ProviderEvent.Closed -> {}
        }
    }

    /** Ends the attachment only. Whatever the thread was doing keeps going, on another leg if it has to. */
    fun disconnect() {
        attempt++
        ending = false
        assistantSpeaking = false
        connectionJob?.cancel()
        media?.close()
        media = null
        session = null
        attachedThreadId = null
        mutableState.update {
            it.copy(
                providerStatus = ProviderStatus.DISCONNECTED,
                providerModel = null,
                voiceMode = false,
                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
            )
        }
    }

    private fun hangUp() {
        val task = attachedThreadId?.let { activeTask(it) }
        // The model chose to end the call, so an answer it has already spoken is complete.
        if (task != null && task.dispatches.none { it.isActive } && !task.awaitingFollowUp) task.complete()
        disconnect()
    }

    /** Cancels the shown thread's turn task. Distinct from ending the call, which leaves it running. */
    fun stopTask() {
        val task = shownThreadId?.let { activeTask(it) } ?: return
        task.interrupt("Stopped.")
    }

    /** Every running task is interrupted with [reason], for when Android will not let them continue. */
    fun interruptAll(reason: String) {
        tasks.values.filter { it.active }.forEach { it.interrupt(reason) }
    }

    fun submit(text: String) {
        val current = state.value
        val opened = session ?: return
        val threadId = attachedThreadId ?: return
        if (current.isLoading || current.isSubmitting || current.errorMessage != null ||
            current.providerStatus != ProviderStatus.CONNECTED || current.voiceMode ||
            text.isBlank()
        ) {
            return
        }
        if (text.length > 1000) {
            mutableState.update { it.copy(providerMessage = "Keep requests under 1,000 characters.") }
            return
        }
        if (activeTask(threadId) != null) {
            mutableState.update { it.copy(providerMessage = "EVA is still working on the last request.") }
            return
        }
        val input = ConversationInput(UUID.randomUUID().toString(), text)
        mutableState.update { it.copy(isSubmitting = true, providerMessage = null) }
        scope.launch {
            val task = startTask(threadId, input.id, text, opened, spoken = false)
            try {
                opened.submit(input)
                opened.requestResponse(ResponseRequest(input.id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (session === opened) {
                    task.interrupt("The request could not be sent.")
                    disconnect()
                    mutableState.update { it.copy(providerMessage = "Could not submit this request. Reconnect to try again.") }
                }
            }
        }
    }

    private suspend fun startTask(
        threadId: String,
        turnId: String,
        request: String,
        leg: ConversationSession,
        spoken: Boolean,
    ): TurnTask {
        store.openTurn(threadId, request, turnId)
        if (request.isNotBlank()) {
            store.append(ThreadItem.UserMessage(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), request, spoken))
            titleFrom(threadId, request)
        }
        val task = TurnTask(threadId, turnId, request, leg)
        tasks[turnId] = task
        mutableState.update { it.copy(working = threadId == shownThreadId) }
        mutableWorking.update { it + threadId }
        return task
    }

    private suspend fun titleFrom(
        threadId: String,
        text: String,
    ) {
        if (store.thread(threadId)?.title == UNTITLED) store.retitle(threadId, text.trim().take(60))
    }

    /** The attachment on [threadId] is gone; its task, if unfinished, must find another leg. */
    private fun detach(
        threadId: String,
        leg: ConversationSession?,
    ) {
        tasks.values.filter { it.threadId == threadId && it.active && it.leg === leg }.forEach { it.legLost() }
    }

    // ---- turn tasks ----

    /**
     * One accepted request until its answer is recorded. Owns its dispatches and whichever
     * provider leg currently speaks for it; nothing here is cancelled by the attachment.
     */
    private inner class TurnTask(
        val threadId: String,
        val turnId: String,
        var request: String,
        leg: ConversationSession,
    ) {
        val taskScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        var leg: ConversationSession? = leg
            private set
        private var background: ConversationSession? = null
        var awaitingFollowUp = false
            private set
        val dispatches = mutableListOf<Job>()
        private var readOnlyCalls = 0
        private var sideEffectCall: String? = null
        private var rehomed = false
        private var stranded = false
        private var lastAnswer = ""
        var active = true
            private set

        fun dispatch(event: ProviderEvent.ToolCallReady) {
            val definition = checkNotNull(definitions[event.capabilityId])
            val id = "provider:${event.call.providerSessionId}:${event.call.callId}"
            val arguments = event.arguments.mapValues { (_, value) -> (value as? JsonPrimitive)?.content }
            val argumentError = if (arguments.values.any { it == null }) "This action binding requires scalar arguments." else null
            val proposal =
                ToolProposal(
                    id,
                    event.capabilityId,
                    arguments.mapValues { it.value.orEmpty() },
                    request.ifBlank { VOICE_REQUEST },
                    threadId = threadId,
                    turnId = turnId,
                )
            val job =
                taskScope.launch {
                    val rejection =
                        when {
                            definition.readOnly && readOnlyCalls >= READ_ONLY_CALLS_PER_TURN -> "Too many lookups for one request."
                            !definition.readOnly && !claimSideEffect(id) -> "One phone action is permitted per request."
                            else -> ToolSchema.error(definition.inputSchema, event.arguments)
                        }
                    if (definition.readOnly && rejection == null) readOnlyCalls++
                    store.append(
                        ThreadItem.ActionCall(
                            UUID.randomUUID().toString(),
                            threadId,
                            turnId,
                            nowMillis(),
                            id,
                            event.capabilityId,
                            definition.title,
                            proposal.arguments,
                        ),
                    )
                    try {
                        val result = dispatcher.execute(proposal, rejection ?: argumentError)
                        deliver(event.call, result.status.name, result.message)
                    } catch (error: ProposalRejectedException) {
                        deliver(event.call, "NOT_EXECUTED", error.message.orEmpty())
                    } catch (error: InvocationPersistenceException) {
                        mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                        interrupt("Action history could not be saved.")
                        disconnect()
                    }
                }
            dispatches += job
        }

        private suspend fun claimSideEffect(callId: String): Boolean {
            if (sideEffectCall == callId) return true
            val claimed = store.reserveSideEffect(turnId, callId)
            if (claimed) sideEffectCall = callId
            return claimed
        }

        private suspend fun deliver(
            call: com.colonelpanic.eva.providers.CallIdentity,
            status: String,
            message: String,
        ) {
            val current = leg
            if (current == null || call.connectionEpoch != current.connectionEpoch) {
                // The leg that asked is gone; the receipt reaches the next leg as evidence instead.
                stranded = true
                return
            }
            try {
                current.submitToolResult(CorrelatedToolResult(call, status, message))
                awaitingFollowUp = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stranded = true
                legLost()
            }
        }

        suspend fun answer(
            text: String,
            truncated: Boolean,
            spoken: Boolean,
        ) {
            awaitingFollowUp = false
            lastAnswer = text
            store.append(ThreadItem.AssistantMessage(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), text, spoken, truncated))
        }

        /** Providers report this once per input, after any tool follow-up, so it is the answer's end. */
        fun generationEnded(status: String) {
            if (status == "completed" || status == "cancelled") complete() else fail("The model could not complete this response.")
        }

        fun complete() {
            if (!active) return
            active = false
            val answered = background != null
            taskScope.launch(NonCancellable) {
                store.closeTurn(turnId, TurnStatus.ANSWERED)
                if (answered) onBackgroundAnswer(BackgroundAnswer(threadId, store.thread(threadId)?.title ?: UNTITLED, lastAnswer))
                finish()
            }
        }

        fun interrupt(reason: String) {
            if (!active) return
            active = false
            taskScope.launch(NonCancellable) {
                store.closeTurn(turnId, TurnStatus.INTERRUPTED)
                store.append(notice(threadId, turnId, NoticeKind.INTERRUPTED, reason))
                finish()
            }
        }

        private fun fail(reason: String) {
            if (!active) return
            active = false
            taskScope.launch(NonCancellable) {
                store.closeTurn(turnId, TurnStatus.FAILED)
                store.append(notice(threadId, turnId, NoticeKind.INTERRUPTED, reason))
                finish()
            }
        }

        private suspend fun finish() {
            background?.let { runCatching { it.close() } }
            background = null
            leg = null
            tasks.remove(turnId)
            mutableWorking.update { it - threadId }
            mutableState.update { if (it.threadId == threadId) it.copy(isSubmitting = false, working = false) else it }
            refresh()
            taskScope.cancel()
        }

        /** The leg this task was answering through is gone. Finish elsewhere once the phone's part settles. */
        fun legLost() {
            if (!active) return
            leg = null
            taskScope.launch {
                dispatches.joinAll()
                if (!active) return@launch
                rehome()
            }
        }

        private suspend fun rehome() {
            if (rehomed) {
                fail("EVA could not continue this request after the call ended.")
                return
            }
            rehomed = true
            stranded = false
            store.append(notice(threadId, turnId, NoticeKind.REHOMED, "Continuing after the call ended"))
            try {
                val items = store.items(threadId)
                val opened =
                    backgroundProviderFactory().open(
                        SessionOpenRequest(
                            instructions(voice = false) + " " + CONTINUATION_NOTE,
                            typedCatalog,
                            history = projectHistory(items, receipts(items)),
                            continuation = Continuation(turnId),
                        ),
                    )
                background = opened
                leg = opened
                taskScope.launch {
                    try {
                        opened.events.takeWhile { it != ProviderEvent.Closed }.collect { event ->
                            if (leg !== opened) return@collect
                            when (event) {
                                is ProviderEvent.Connected -> {
                                    opened.requestResponse(ResponseRequest(turnId))
                                }

                                is ProviderEvent.ToolCallReady -> {
                                    if (event.call.inputId == turnId) dispatch(event)
                                }

                                is ProviderEvent.AssistantText -> {
                                    if (event.inputId ==
                                        turnId
                                    ) {
                                        answer(event.text, event.truncated, spoken = false)
                                    }
                                }

                                is ProviderEvent.ResponseEnded -> {
                                    if (event.inputId == turnId) generationEnded(event.status)
                                }

                                is ProviderEvent.Failure -> {
                                    throw IllegalStateException(event.message)
                                }

                                else -> {}
                            }
                        }
                        if (active) fail("The connection ended before the response completed.")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        fail(error.message?.take(300) ?: "EVA could not continue this request.")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error.message?.take(300) ?: "EVA could not continue this request.")
            }
        }
    }

    // ---- prompts ----

    private fun instructions(voice: Boolean): String =
        if (!voice) {
            "You are EVA, an assistant running on the user's Android phone. " +
                "Help conversationally and use the supplied tools for phone actions. " +
                "Ask for missing information. Never claim sending a message when only a draft was opened. " +
                clock()
        } else {
            "You are EVA, a voice assistant running on the user's Android phone. " +
                "Keep spoken replies short. Use the supplied tools for phone actions and say " +
                "what the tool result reports. Never claim sending a message when only a draft was opened. " +
                "Spoken names may be transcribed with the wrong spelling. For read-only lookups such as " +
                "contacts search, first assess how ambiguous the name you heard is. If it could reasonably " +
                "have multiple spellings, generate and rank the plausible spellings and phonetic variants, " +
                "deduplicate them, and proactively search the most likely variants. After the initial lookup, " +
                "make up to ${voiceLookupRetries()} additional lookup queries in total. Use that budget for " +
                "the best spelling variants and, when a full name does not find a clear match, the first name " +
                "or last name by itself. Do not spend queries on implausible variations. " +
                "Use only query forms supported by the tool; do not put several alternatives into one query " +
                "unless the tool supports it. Respect spellings explicitly supplied by the user. " +
                "Use returned records to identify matches; never invent a person or contact detail. " +
                "If different people plausibly match, ask which one the user means before acting. " +
                "If these lookups still find nothing, ask for the spelling or another identifying detail. " +
                "Apply these retries only to read-only lookups, never to sending, calling, or opening apps. " +
                "When the user is finished, because they say goodbye, say that is all, or ask you to " +
                "hang up, say a brief goodbye and then end the conversation with its tool. " +
                clock()
        }

    private fun clock(): String {
        val now = java.util.Calendar.getInstance()
        val format = java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm zzz", java.util.Locale.US)
        return "The user's current local time is ${format.format(now.time)}, " +
            "which is ${now.timeInMillis} in Unix milliseconds."
    }

    companion object {
        const val UNTITLED = "New conversation"
        const val READ_ONLY_CALLS_PER_TURN = 8
        private const val VOICE_REQUEST = "Voice request"
        private const val CONTINUATION_NOTE =
            "The voice call for this request has ended. Finish the request in text using the receipts in the " +
                "conversation; do not repeat actions that already have a receipt."

        /** Bounds the wait for a goodbye whose end is never reported. */
        private const val END_SPEECH_LIMIT_MILLIS = 10_000L
        private const val PLAYOUT_TAIL_MILLIS = 500L

        /** Hangs up rather than acting on the phone, so it bypasses the dispatcher and journal. */
        val END_CONVERSATION =
            ProviderToolDefinition(
                "eva.session.end",
                "End the conversation",
                "Hang up this voice conversation. Call it when the user says goodbye, says they are done, or asks " +
                    "you to hang up, after a brief spoken goodbye; the goodbye finishes playing before the call ends. " +
                    "Do not call it while a request is unfinished or you are waiting for the user to answer.",
                Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
            )

        fun catalogOf(tools: List<ProviderToolDefinition>) =
            ProviderToolCatalog(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(tools.joinToString { "${it.capabilityId}:${it.description}:${it.inputSchema}" }.toByteArray())
                    .joinToString("") { "%02x".format(it) },
                tools,
            )
    }
}

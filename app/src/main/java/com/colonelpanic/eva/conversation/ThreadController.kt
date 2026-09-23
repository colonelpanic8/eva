package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationPersistenceException
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ProposalRejectedException
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.capability.modelDescription
import com.colonelpanic.eva.conversation.prompt.AssembledPrompt
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptContext
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.CallIdentity
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Threads are durable; a voice call or text connection is an attachment that comes and goes.
 * The work of a turn belongs to its [TurnTask], which is not a child of the attachment: when
 * the call ends with the turn unfinished, the task re-homes onto a background text leg and
 * finishes there. See docs/architecture.md. Calls and [scope] are confined to the UI dispatcher.
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
    private val awaitCapabilities: suspend () -> Unit = {},
    private val voiceLookupRetries: () -> Int = { 5 },
    /** Silence after a one-request call's action is reported before EVA hangs up; 0 never does. */
    private val quietHangUpMillis: () -> Long = { 5_000L },
    /** The followed wording of EVA's own tools and notes. */
    private val wording: () -> Wording = { Wording.bundled },
    private val voiceKeywords: suspend () -> List<String> = { emptyList() },
    /** Capabilities the user has switched off. They are left out of the catalog entirely. */
    private val hiddenCapabilities: () -> Set<String> = { emptySet() },
    /** Read at every connection, so an edit to the prompt file applies to the next session. */
    private val prompt: suspend () -> PromptConfig = { PromptDefaults.config },
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

    /**
     * Signals the model hanging up, as opposed to a disconnect the user or a failure caused.
     * A surface that only exists to host the call, such as the assistant panel, can close itself
     * on this; nothing is replayed, so a surface that was not listening at the time stays put.
     */
    private val mutableHangUps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hangUps = mutableHangUps.asSharedFlow()
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
    private var endingToken = 0
    private var endReason = ""
    private var endRequest: CallIdentity? = null

    /** The model asked to hang up with an action still to report; it hangs up once that is said. */
    private var hangUpDeferred = false

    /** Responses that proposed a phone action, so a hang-up in the same breath waits for its result. */
    private val actionResponses = mutableSetOf<String>()
    private var assistantSpeaking = false

    /** A one-request call whose action is reported hangs up if the user stays quiet after it. */
    private var quietArmed = false
    private var quietHangUp: Job? = null

    /** How each attachment ended, keyed by attempt, for its closing notice. */
    private val endReasons = mutableMapOf<Int, String>()

    private data class ConnectionTools(
        val snapshot: CapabilityRegistry.Snapshot,
        val catalog: ProviderToolCatalog,
        val voice: Boolean,
        val callMode: VoiceCallMode? = null,
    )

    private val connectionTools = mutableMapOf<ConversationSession, ConnectionTools>()

    /**
     * Read when a session opens, not once at construction, so switching a capability off takes
     * effect on the next connection. A live session keeps the catalog it was opened with.
     */
    private fun phoneTools(
        snapshot: CapabilityRegistry.Snapshot,
        voice: Boolean,
    ) = com.colonelpanic.eva.capability.CatalogAdmission
        .select(
            snapshot.catalog.filterNot {
                it.id in hiddenCapabilities()
            },
            if (voice) 2 else 0,
        ).admitted
        .map { definition ->
            val tool = ProviderToolDefinition(definition.id, definition.title, definition.modelDescription(), definition.inputSchema)
            // An extension's own words are untrusted data; only EVA's tools take followed wording.
            if (definition.source == null) wording().describe(tool) else tool
        }

    /**
     * What each source offering tools on this connection says about how they fit together. It is
     * the source's text, so it is quoted as data under EVA's own framing, like tool metadata.
     */
    private fun extensionGuidance(
        snapshot: CapabilityRegistry.Snapshot,
        catalog: ProviderToolCatalog,
    ): String {
        val notes =
            catalog.tools
                .mapNotNull { snapshot.definitions[it.capabilityId] }
                .filter { it.source != null && it.guidance != null }
                .distinctBy { it.source!!.id }
                .map { definition ->
                    JsonObject(
                        mapOf("source" to JsonPrimitive(definition.source!!.title), "guidance" to JsonPrimitive(definition.guidance)),
                    )
                }
        if (notes.isEmpty()) return ""
        return "\n\n" + wording().message(Wording.EXTENSION_GUIDANCE) + "\n" + JsonArray(notes)
    }

    /** The prompt and the catalog are decided together: components rewrite and hide tools. */
    private suspend fun assemble(
        voice: Boolean,
        callMode: VoiceCallMode? = null,
    ): AssembledPrompt =
        prompt()
            .validated(PromptDefaults.VARIABLES)
            .let { configured -> if (voice && callMode != null) configured.selectCallMode(callMode) else configured }
            .assemble(
                PromptContext(voice, mapOf("clock" to clock(), "lookup_retries" to voiceLookupRetries().toString())),
            )

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
        return repository.byCallIds(wanted)
    }

    private fun activeTask(threadId: String): TurnTask? = tasks.values.firstOrNull { it.threadId == threadId && it.active }

    /**
     * Provider input ids are leg-local: a realtime session numbers its turns from scratch, so
     * two sessions on one thread would collide. Turn identity is the store's, and a leg's ids
     * are resolved against the leg that issued them.
     */
    private fun taskFor(
        leg: ConversationSession,
        inputId: String,
    ): TurnTask? = tasks.values.firstOrNull { it.leg === leg && it.inputId == inputId }

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
        callMode: VoiceCallMode? = null,
    ) = connectSession(link, voice = true, newThread = newThread, callMode = callMode)

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
        callMode: VoiceCallMode? = null,
    ) {
        if (state.value.isLoading || state.value.errorMessage != null) return
        end(ENDED_FOR_NEW_SESSION)
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
                    // Assembled before any provider work so a broken file fails here, with its message.
                    awaitCapabilities()
                    val assembled = assemble(voice, callMode)
                    // Only a spoken session is something the model can hang up. The catalog is built per
                    // connection because a switched-off capability and the enabled components both decide
                    // which tools are offered and what they say.
                    val snapshot = registry.snapshot
                    val connectionCatalog =
                        catalogOf(
                            assembled.apply(
                                (if (voice) listOf(wording().describe(END_CONVERSATION), DEFER_TO_TEXT) else emptyList()) +
                                    phoneTools(snapshot, voice),
                            ),
                            snapshot.revision,
                        )
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
                                            end("ended: audio failed (${audioState.reason.message})")
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
                                assembled.instructions + extensionGuidance(snapshot, connectionCatalog),
                                connectionCatalog,
                                // Captions only; a typed session has no audio to transcribe.
                                if (voice) voiceKeywords() else emptyList(),
                                history = projectHistory(items, receipts(items)),
                            ),
                        )
                    openedSession = opened
                    connectionTools[opened] = ConnectionTools(snapshot, connectionCatalog, voice, assembled.callMode)
                    currentCoroutineContext().ensureActive()
                    session = opened
                    opened.events.takeWhile { it != ProviderEvent.Closed }.collect { event ->
                        if (thisAttempt != attempt) return@collect
                        onAttachedEvent(event, opened, threadId, voice)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.message?.take(300) ?: "Provider connection failed."
                    endReasons.putIfAbsent(thisAttempt, "ended: $message")
                    if (thisAttempt == attempt) {
                        mutableState.update { it.copy(providerMessage = message) }
                    }
                } finally {
                    currentCoroutineContext().cancelChildren()
                    val reason = endReasons.remove(thisAttempt) ?: "ended: the provider closed the connection"
                    if (openedSession != null && threadId != null) {
                        val label = "${if (voice) "Call" else "Session"} $reason"
                        withContext(NonCancellable) { store.append(notice(threadId, null, NoticeKind.SESSION_ENDED, label)) }
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
                    connectionTools.remove(openedSession)
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
                check(event.catalogRevision == connectionTools.getValue(opened).catalog.revision)
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
                disarmQuietHangUp()
                if (taskFor(opened, event.inputId) == null && voice) {
                    // A spoken turn has no typed input; the response is the turn, and its transcript
                    // may still be in flight. Nothing else may own this thread's next answer.
                    activeTask(threadId)?.let { previous ->
                        if (previous.delegated) return
                        if (previous.dispatches.none { it.isActive }) previous.complete()
                    }
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
                check(event.call.catalogRevision == connectionTools.getValue(opened).catalog.revision)
                if (voice && event.capabilityId == END_CONVERSATION.capabilityId) {
                    // Nothing runs on the phone and, unless the hang-up is deferred, no result is
                    // returned, so the model is not prompted to speak again. Its goodbye plays out first.
                    endCall(ENDED_BY_MODEL, event.call)
                } else if (voice && event.capabilityId == DEFER_TO_TEXT.capabilityId) {
                    val task = taskFor(opened, event.call.inputId)
                    val instruction = (event.arguments["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    if (task == null ||
                        connectionTools[opened]?.catalog?.tools?.any { it.capabilityId == DEFER_TO_TEXT.capabilityId } != true ||
                        event.arguments.size != 1 || instruction.isNullOrEmpty() || instruction.length > 1000
                    ) {
                        opened.submitToolResult(
                            CorrelatedToolResult(
                                event.call,
                                "NOT_EXECUTED",
                                "Provide a task of 1 to 1,000 characters for an active request.",
                            ),
                        )
                    } else {
                        task.delegateToText(instruction, event.call)
                    }
                } else {
                    if (voice) actionResponses += event.call.generationId
                    taskFor(opened, event.call.inputId)?.dispatch(event)
                }
            }

            is ProviderEvent.UserSpeaking -> {
                disarmQuietHangUp()
            }

            is ProviderEvent.AssistantSpeaking -> {
                assistantSpeaking = event.speaking
                if (quietArmed) {
                    if (event.speaking) quietHangUp?.cancel() else startQuietTimer()
                }
                if (ending && !event.speaking) {
                    val token = endingToken
                    scope.launch {
                        // The phone still holds a little audio after the server drains.
                        delay(PLAYOUT_TAIL_MILLIS)
                        finishEnding(token)
                    }
                }
            }

            is ProviderEvent.AssistantText -> {
                taskFor(opened, event.inputId)?.answer(event.text, event.truncated, voice)
            }

            is ProviderEvent.ResponseEnded -> {
                val task = taskFor(opened, event.inputId)
                task?.generationEnded(event.status)
                if (voice && hangUpDeferred) {
                    endCall(ENDED_BY_MODEL)
                } else if (voice && task?.actionServiced == true && connectionTools[opened]?.callMode == VoiceCallMode.ONE_REQUEST) {
                    // The model decides when a request is fully served, but one whose action is done and
                    // reported does not stay open just because it forgot to hang up: silence ends it.
                    quietArmed = quietHangUpMillis() > 0
                    if (quietArmed && !assistantSpeaking) startQuietTimer()
                }
            }

            is ProviderEvent.Failure -> {
                throw IllegalStateException(event.message)
            }

            ProviderEvent.Closed -> {}
        }
    }

    private fun startQuietTimer() {
        quietHangUp?.cancel()
        val thisAttempt = attempt
        quietHangUp =
            scope.launch {
                delay(quietHangUpMillis())
                if (thisAttempt == attempt && quietArmed) {
                    quietArmed = false
                    endCall(ENDED_AFTER_REQUEST)
                }
            }
    }

    private fun disarmQuietHangUp() {
        quietArmed = false
        quietHangUp?.cancel()
        quietHangUp = null
    }

    /** Hangs up once whatever EVA is saying has finished playing. [request] is the model's own call to hang up. */
    private fun endCall(
        reason: String,
        request: CallIdentity? = null,
    ) {
        if (ending) return
        ending = true
        endReason = reason
        endRequest = request
        val token = ++endingToken
        scope.launch {
            if (assistantSpeaking) delay(END_SPEECH_LIMIT_MILLIS)
            finishEnding(token)
        }
    }

    /**
     * A hang-up proposed alongside an action, or while one is still to be reported, would strand
     * the result: the call ends before it is spoken. The model is told to report it first, and the
     * call ends after that response instead.
     */
    private fun finishEnding(token: Int) {
        if (!ending || token != endingToken) return
        val request = endRequest
        val task = attachedThreadId?.let { activeTask(it) }
        val unreported = task != null && (task.dispatches.any { it.isActive } || task.awaitingFollowUp)
        val opened = session
        if (request != null && opened != null && (unreported || request.generationId in actionResponses)) {
            ending = false
            endRequest = null
            hangUpDeferred = true
            scope.launch {
                runCatching {
                    opened.submitToolResult(
                        CorrelatedToolResult(request, "NOT_EXECUTED", wording().message(Wording.HANG_UP_DEFERRED)),
                    )
                }
            }
            return
        }
        hangUp(endReason)
    }

    /** Ends the attachment only. Whatever the thread was doing keeps going, on another leg if it has to. */
    fun disconnect() = end(ENDED_BY_USER)

    private fun end(reason: String) {
        if (connectionJob?.isActive == true) endReasons.putIfAbsent(attempt, reason)
        attempt++
        ending = false
        endRequest = null
        hangUpDeferred = false
        disarmQuietHangUp()
        actionResponses.clear()
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

    private fun hangUp(reason: String) {
        val task = attachedThreadId?.let { activeTask(it) }
        // The model chose to end the call, so an answer it has already spoken is complete.
        if (task != null && !task.delegated && task.dispatches.none { it.isActive } && !task.awaitingFollowUp) task.complete()
        end(reason)
        mutableHangUps.tryEmit(Unit)
    }

    /** Cancels the shown thread's turn task. Distinct from ending the call, which leaves it running. */
    fun stopTask() {
        val task = shownThreadId?.let { activeTask(it) } ?: return
        task.interrupt("Stopped.")
    }

    fun voiceUnavailable(reason: String) {
        end(reason)
        mutableState.update { it.copy(providerMessage = reason) }
    }

    /** Every running task is interrupted with [reason], for when Android will not let them continue. */
    fun interruptAll(reason: String) {
        tasks.values.filter { it.active }.forEach { it.interrupt(reason) }
    }

    fun submit(text: String) {
        val current = state.value
        val opened = session ?: return
        val threadId = attachedThreadId ?: return
        if (current.isLoading || current.errorMessage != null ||
            current.providerStatus != ProviderStatus.CONNECTED || current.voiceMode ||
            text.isBlank()
        ) {
            return
        }
        if (activeTask(threadId) != null) {
            mutableState.update { it.copy(providerMessage = "EVA is still working on the last request.") }
            return
        }
        if (text.length > 1000) {
            mutableState.update { it.copy(providerMessage = "Keep requests under 1,000 characters.") }
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
                    end("ended: the request could not be sent")
                    mutableState.update { it.copy(providerMessage = "Could not submit this request. Reconnect to try again.") }
                }
            }
        }
    }

    private suspend fun startTask(
        threadId: String,
        inputId: String,
        request: String,
        leg: ConversationSession,
        spoken: Boolean,
    ): TurnTask {
        val turnId = UUID.randomUUID().toString()
        store.openTurn(threadId, request, turnId)
        if (request.isNotBlank()) {
            store.append(ThreadItem.UserMessage(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), request, spoken))
            titleFrom(threadId, request)
        }
        val task = TurnTask(threadId, turnId, inputId, request, leg)
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
        /** What the current leg calls this turn; the store's id is [turnId]. */
        var inputId: String,
        var request: String,
        leg: ConversationSession,
    ) {
        val taskScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        var leg: ConversationSession? = leg
            private set
        private var background: ConversationSession? = null
        private var tools = connectionTools.getValue(leg)
        var awaitingFollowUp = false
            private set
        val dispatches = mutableListOf<Job>()

        /** This request's phone action completed or was handed off, so the request has been served. */
        var actionServiced = false
            private set
        private var readOnlyCalls = 0
        private val dispatchLock = Mutex()
        private val admittedCalls = mutableSetOf<String>()
        private var mutationUncertain = false
        private var rehomed = false
        var delegated = false
            private set
        private var stranded = false
        private var lastAnswer = ""
        var active = true
            private set

        fun dispatch(event: ProviderEvent.ToolCallReady) {
            val context = tools
            val definition = context.snapshot.definitions[event.capabilityId]
            val id = "provider:${event.call.providerSessionId}:${event.call.callId}"
            val arguments =
                event.arguments.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> value.content
                        is JsonArray -> value.toString()
                        else -> null
                    }
                }
            val argumentError = if (arguments.values.any { it == null }) "This action binding requires scalar or list arguments." else null
            val proposal =
                ToolProposal(
                    id,
                    event.capabilityId,
                    arguments.mapValues { it.value.orEmpty() },
                    request.ifBlank { VOICE_REQUEST },
                    catalogRevision = context.snapshot.revision,
                    threadId = threadId,
                    turnId = turnId,
                    interactionMode = if (context.voice) InteractionMode.VOICE else InteractionMode.TYPED,
                    onWaiting = { mutableState.update { it.copy(providerMessage = "Still waiting for the action…") } },
                )
            val job =
                taskScope.launch {
                    dispatchLock.withLock {
                        if (!active) return@withLock
                        val rejection =
                            when {
                                event.call.catalogRevision != context.catalog.revision || definition == null ||
                                    context.catalog.tools.none {
                                        it.capabilityId == event.capabilityId
                                    } -> {
                                    "This action was not offered in this connection. Nothing was executed."
                                }

                                !definition.readOnly && !definition.bookkeeping && mutationUncertain -> {
                                    "A previous action may have changed external state. " +
                                        "Verify its outcome before requesting more changes. Nothing was executed."
                                }

                                id !in admittedCalls && admittedCalls.size >= CALLS_PER_TURN -> {
                                    "The action limit for this request was reached. Nothing was executed."
                                }

                                definition.readOnly && readOnlyCalls >= READ_ONLY_CALLS_PER_TURN -> {
                                    "Too many lookups for one request."
                                }

                                else -> {
                                    ToolSchema.error(definition.inputSchema, event.arguments)
                                }
                            }
                        if (rejection == null && argumentError == null && admittedCalls.add(id) &&
                            definition?.readOnly == true
                        ) {
                            readOnlyCalls++
                        }
                        store.append(
                            ThreadItem.ActionCall(
                                UUID.randomUUID().toString(),
                                threadId,
                                turnId,
                                nowMillis(),
                                id,
                                event.capabilityId,
                                definition?.title ?: event.capabilityId,
                                proposal.arguments,
                            ),
                        )
                        try {
                            val result = dispatcher.execute(proposal, rejection ?: argumentError)
                            val changesPhone = definition?.readOnly == false && !definition.bookkeeping
                            if (changesPhone &&
                                (result.status == InvocationStatus.COMPLETED || result.status == InvocationStatus.HANDED_OFF)
                            ) {
                                actionServiced = true
                            }
                            if (changesPhone &&
                                result.status in setOf(InvocationStatus.UNKNOWN, InvocationStatus.FAILED)
                            ) {
                                mutationUncertain = true
                            }
                            deliver(event.call, result.status.name, result.message, result.provenance, result.data)
                        } catch (error: ProposalRejectedException) {
                            deliver(event.call, "NOT_EXECUTED", error.message.orEmpty())
                        } catch (error: InvocationPersistenceException) {
                            mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                            interrupt("Action history could not be saved.")
                            end("ended: action history could not be saved")
                        }
                    }
                }
            dispatches += job
        }

        private suspend fun deliver(
            call: com.colonelpanic.eva.providers.CallIdentity,
            status: String,
            message: String,
            provenance: com.colonelpanic.eva.capability.ReceiptProvenance? = null,
            data: JsonObject? = null,
        ) {
            val current = leg
            if (current == null || call.connectionEpoch != current.connectionEpoch) {
                // The leg that asked is gone; the receipt reaches the next leg as evidence instead.
                stranded = true
                return
            }
            try {
                current.submitToolResult(CorrelatedToolResult(call, status, message, data, provenance))
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

        fun delegateToText(
            instruction: String,
            call: CallIdentity,
        ) {
            if (!active || rehomed) return
            val voiceLeg = leg ?: return
            delegated = true
            leg = null
            taskScope.launch {
                dispatches.joinAll()
                val started = active && rehome(instruction)
                try {
                    voiceLeg.submitToolResult(
                        CorrelatedToolResult(
                            call,
                            if (started) "HANDED_OFF" else "NOT_EXECUTED",
                            if (started) {
                                "The request is continuing with EVA's text agent. Tell the user briefly; do not claim it is finished."
                            } else {
                                "EVA could not start text continuation. Tell the user the handoff failed."
                            },
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The text leg, once started, owns the turn even if the call cannot receive this result.
                }
            }
        }

        private suspend fun rehome(instruction: String? = null): Boolean {
            if (rehomed) {
                fail("EVA could not continue this request after the call ended.")
                return false
            }
            rehomed = true
            stranded = false
            inputId = turnId
            try {
                store.append(
                    notice(
                        threadId,
                        turnId,
                        NoticeKind.REHOMED,
                        if (instruction == null) "Continuing after the call ended" else "Continuing in text",
                    ),
                )
                val items = store.items(threadId)
                awaitCapabilities()
                val assembled = assemble(voice = false)
                val snapshot = registry.snapshot
                val catalog = catalogOf(assembled.apply(phoneTools(snapshot, false)), snapshot.revision)
                tools = ConnectionTools(snapshot, catalog, false)
                val opened =
                    backgroundProviderFactory().open(
                        SessionOpenRequest(
                            assembled.instructions + extensionGuidance(snapshot, catalog) + "\n\n" +
                                (
                                    instruction?.let { "The voice assistant delegated this request to text. Finish it: $it" }
                                        ?: wording().message(Wording.CONTINUATION)
                                ),
                            catalog,
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
                                    check(event.catalogRevision == catalog.revision)
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
                return true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error.message?.take(300) ?: "EVA could not continue this request.")
                return false
            }
        }
    }

    private fun clock(): String {
        val now = java.util.Calendar.getInstance()
        val format = java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm zzz", java.util.Locale.US)
        return "The user's current local time is ${format.format(now.time)}, " +
            "which is ${now.timeInMillis} in Unix milliseconds."
    }

    companion object {
        const val UNTITLED = "New conversation"
        const val READ_ONLY_CALLS_PER_TURN = 24
        const val CALLS_PER_TURN = 32
        private const val VOICE_REQUEST = "Voice request"

        /** Bounds the wait for a goodbye whose end is never reported. */
        private const val END_SPEECH_LIMIT_MILLIS = 10_000L
        private const val PLAYOUT_TAIL_MILLIS = 500L
        private const val ENDED_BY_USER = "ended by you"
        private const val ENDED_BY_MODEL = "ended by EVA with its end-call tool"
        private const val ENDED_AFTER_REQUEST = "ended by EVA: the request was done and the line went quiet"
        private const val ENDED_FOR_NEW_SESSION = "ended for a new session"

        /**
         * Hangs up rather than acting on the phone, so it bypasses the dispatcher and journal. Its
         * wording is [Wording]'s, reworded again by whichever call component is enabled.
         */
        val END_CONVERSATION by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    PromptDefaults.END_CONVERSATION_ID,
                    "End the conversation",
                    "",
                    Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
                ),
            )
        }

        val DEFER_TO_TEXT =
            ProviderToolDefinition(
                "eva.session.defer_to_text",
                "Continue in text",
                "Hand the current request to EVA's background text agent when it needs several lookups or steps, such as " +
                    "finding a Paseo workspace, choosing among its agents, and reading their messages. Give the text " +
                    "agent a self-contained task. It receives EVA's enabled phone and extension tools and the request's " +
                    "action history. This starts work; it does not mean the task is complete. Tell the user briefly " +
                    "that the answer will arrive in text.",
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{"task":{"type":"string","minLength":1,"maxLength":1000}},"required":["task"],"additionalProperties":false}""",
                    ).jsonObject,
            )

        fun catalogOf(
            tools: List<ProviderToolDefinition>,
            revision: String = "",
        ) = ProviderToolCatalog(
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    (revision + tools.joinToString { "${it.capabilityId}:${it.title}:${it.description}:${it.inputSchema}" })
                        .toByteArray(),
                ).joinToString("") { "%02x".format(it) },
            tools,
        )
    }
}

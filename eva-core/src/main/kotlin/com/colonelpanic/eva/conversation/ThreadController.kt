package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CallEnding
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationPersistenceException
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ProposalRejectedException
import com.colonelpanic.eva.capability.SkillCapabilities
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.capability.UnsupportedJournalVersionException
import com.colonelpanic.eva.capability.modelDescription
import com.colonelpanic.eva.conversation.prompt.AssembledPrompt
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptContext
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.TaskPhase
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.CallRejection
import com.colonelpanic.eva.providers.Continuation
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.MODEL_RESULT_CHARS
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import com.colonelpanic.eva.providers.boundedResultText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
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
    /** The user's per-action overrides of whether a successful action ends the voice call. */
    private val callEndings: () -> Map<String, CallEnding> = { emptyMap() },
    /** Configured messaging bridges, service name to label, named to the model on the shared messaging tools. */
    private val messagingBridges: () -> Map<String, String> = { emptyMap() },
    /** Enabled skills, named to the model on the skill tool. */
    private val skills: () -> List<com.colonelpanic.eva.skills.Skill> = { emptyList() },
    /** Capabilities the user has switched off. They are left out of the catalog entirely. */
    private val hiddenCapabilities: () -> Set<String> = { emptySet() },
    /** Read at every connection, so an edit to the prompt file applies to the next session. */
    private val prompt: suspend () -> PromptConfig = { PromptDefaults.config },
    /** Notification fallback when a background outcome cannot be delivered to attached voice. */
    private val onBackgroundAnswer: (BackgroundAnswer) -> Unit = {},
    private val onWorkAccepted: suspend () -> Unit = {},
    private val stallPeriodMillis: () -> Long = { 180_000L },
    private val onBackgroundAnswerDelivered: (BackgroundAnswer) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deviceTasks: com.colonelpanic.eva.devicecontrol.DeviceTaskCoordinator? = null,
) {
    private data class PendingQuestion(
        val threadId: String,
        var evidence: QuestionEvidence,
        val target: ConversationSession? = null,
        val call: CallIdentity? = null,
        val deviceKey: String? = null,
    )

    private val questions = linkedMapOf<String, PendingQuestion>()
    private var nextQuestionOrder = 0L
    private val questionWrites = Mutex()
    private val transcriptItems = mutableMapOf<Pair<ConversationSession, String>, String>()

    private val deviceSpeechOwners = mutableMapOf<String, DeviceSpeech>()

    /** Question delivery state is local to each voice attachment. */
    private val questionRelays = mutableMapOf<Pair<ConversationSession, String>, QuestionRelay>()

    /** [held] while the voice model has the question queued or delivered; [failed] once its delivery failed. */
    private data class QuestionRelay(
        val key: String,
        val held: Boolean,
        val failed: Boolean = false,
    )

    /** Speech ownership captured before transcription; [question] selects the fallback answer target. */
    private data class DeviceSpeech(
        val threadId: String,
        val turnId: String,
        val question: String?,
    )

    data class BackgroundAnswer(
        val threadId: String,
        val title: String,
        val answer: String,
        val taskId: String = "",
        val status: TurnStatus = TurnStatus.ANSWERED,
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
    private val progress = mutableMapOf<String, TaskProgressRecord>()
    private val mutableTaskSnapshots = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    private val taskTrace = TaskStateTrace()
    val taskSnapshots = mutableTaskSnapshots.asStateFlow()
    private var workCoverage = WorkCoverage.NONE
    private val coverageNotices = mutableSetOf<Pair<String, String>>()

    fun setWorkCoverage(coverage: WorkCoverage) {
        workCoverage = coverage
        refreshTaskSnapshots()
    }

    fun workCoverageNotice(reason: String): Boolean {
        val affected = tasks.values.filter { it.active && !voiceCovered(it) && coverageNotices.add(it.turnId to reason) }
        affected.forEach { task ->
            launchFinalization(task) {
                store.append(notice(task.threadId, task.turnId, NoticeKind.COVERAGE_LIMIT, reason))
                requestRefresh()
            }
        }
        return affected.isNotEmpty()
    }

    /** The host ticks this clock while running, independently of provider traffic. */
    fun refreshTaskSnapshots() {
        val now = nowMillis()
        val device = deviceTasks?.running?.value
        mutableTaskSnapshots.value =
            tasks.values.map { task ->
                val record = progress.getOrPut(task.turnId) { TaskProgressRecord(now) }
                val ownsDevice = device?.turnId == task.turnId
                val holdsLease = deviceTasks?.lease?.owner?.let { it in task.actionCallIds } == true
                val baseState =
                    taskState(
                        task.active,
                        task.turnId in deviceTasks?.releasing?.value.orEmpty(),
                        pendingQuestions(task.threadId).any { it.evidence.taskId == task.turnId },
                        task.actionCallIds.any { it in deviceTasks?.waitingForLease?.value.orEmpty() },
                        ownsDevice,
                        record.waiting,
                        task.isBackground && task.backgroundState == "CONNECTING",
                    )
                TaskSnapshot(
                    task.threadId,
                    threads.value.firstOrNull { it.id == task.threadId }?.title ?: task.request.take(60),
                    task.turnId,
                    task.request,
                    when {
                        ownsDevice -> TaskKind.DEVICE_TASK
                        task.delegated -> TaskKind.DELEGATED_TEXT_AGENT
                        task.isBackground -> TaskKind.REHOMED_CONTINUATION
                        connectionTools[task.originLeg]?.voice == true -> TaskKind.VOICE_TURN
                        else -> TaskKind.TYPED_TURN
                    },
                    baseState,
                    record.startedAt,
                    record.lastProgressAt,
                    task.actionCallIds.size,
                    record.lastActionTitle,
                    record.lastActionStatus,
                    holdsLease,
                    workCoverage,
                    baseState.canStall() && now - record.lastProgressAt >= stallPeriodMillis(),
                    pendingQuestions(task.threadId).firstOrNull { it.evidence.taskId == task.turnId }?.evidence?.question,
                    pendingQuestions(task.threadId).filter { it.evidence.taskId == task.turnId }.map { it.evidence },
                )
            }
        taskTrace.observe(mutableTaskSnapshots.value)
    }

    private fun taskProgress(turnId: String) {
        progress[turnId]?.lastProgressAt = nowMillis()
    }

    private fun providerProgress(
        event: ProviderEvent,
        leg: ConversationSession,
    ) {
        traceProviderEvent(event, leg.connectionEpoch)
        val input =
            when (event) {
                is ProviderEvent.ResponseStarted -> event.inputId
                is ProviderEvent.ResponseEnded -> event.inputId
                is ProviderEvent.AssistantText -> event.inputId
                is ProviderEvent.ToolCallReady -> event.call.inputId
                is ProviderEvent.Transcript -> event.inputId
                else -> null
            } ?: return
        tasks.values
            .filter { it.active && it.leg === leg && it.inputId == input }
            .forEach { taskProgress(it.turnId) }
    }

    fun stopTask(taskId: String) {
        EvaTrace.info("task.stop", "task" to taskId, "known" to (taskId in tasks))
        tasks[taskId]?.interrupt(wording().message(Wording.TURN_STOP_REQUESTED))
    }

    fun stopAllTasks() = interruptAll(wording().message(Wording.TURN_STOP_REQUESTED))

    /** Stops work the live call is not carrying, for controls outside the conversation. */
    fun stopBackgroundTasks() {
        tasks.values.filter { it.active && !voiceCovered(it) }.forEach { it.interrupt(wording().message(Wording.TURN_STOP_REQUESTED)) }
    }

    fun forceStopTask(taskId: String) {
        tasks[taskId]?.forceStop()
    }

    private val mutationLocks = mutableMapOf<String, Mutex>()

    /** Mutations a force stop stopped waiting for, by thread; their effects are uncertain until they return. */
    private val abandonedMutations = mutableMapOf<String, MutableSet<Job>>()

    private fun abandonedMutationOn(threadId: String) = synchronized(abandonedMutations) { threadId in abandonedMutations }

    private fun releaseAbandoned(
        threadId: String,
        job: Job,
    ) = synchronized(abandonedMutations) {
        val jobs = abandonedMutations[threadId] ?: return@synchronized
        jobs -= job
        if (jobs.isEmpty()) abandonedMutations -= threadId
    }

    private data class VoiceTurn(
        val turnId: String,
        val announceOnly: Boolean,
    )

    private val voiceTurns = mutableMapOf<Pair<ConversationSession, String>, VoiceTurn>()
    private val pendingAnnouncements = mutableMapOf<Pair<ConversationSession, String>, BackgroundAnswer>()
    private val mutableWorkCoverage = MutableStateFlow(false)
    val needsWorkCoverage = mutableWorkCoverage.asStateFlow()

    private fun voiceCovered(task: TurnTask): Boolean =
        session != null && task.leg === session && connectionTools[session]?.voice == true &&
            state.value.providerStatus == ProviderStatus.CONNECTED && !task.delegated

    private fun updateWorkCoverage() {
        mutableWorkCoverage.value =
            tasks.values.any { !it.coverageLost } || journalJobs.values.any { !it.coverageLost }
        refreshTaskSnapshots()
    }

    /** Each turn's job until it completes, which can be after the turn leaves [tasks]. */
    private val turnJobs: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    private val journalJobs = java.util.concurrent.ConcurrentHashMap<Job, TurnTask>()

    private fun launchFinalization(
        owner: TurnTask?,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        scope.launch(NonCancellable, start = CoroutineStart.LAZY, block = block).also { job ->
            turnJobs += job
            if (owner != null) journalJobs[job] = owner
            job.invokeOnCompletion {
                turnJobs -= job
                journalJobs.remove(job)
                scope.launch { updateWorkCoverage() }
            }
            updateWorkCoverage()
            job.start()
        }

    /** Refreshes run one at a time, so an older read can never publish over a newer one. */
    private val refreshLock = Mutex()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private var media: RealtimeMediaSession? = null
    private var connectionJob: Job? = null
    private var session: ConversationSession? = null
    private var attempt = 0
    private var attachedThreadId: String? = null
    private var lastTextLink: String? = null
    private var submittingInputId: String? = null
    private var ending = false
    private var endingToken = 0
    private var endReason = ""
    private var endRequest: CallIdentity? = null

    /** The model asked to hang up with an action still to report; it hangs up once that is said. */
    private var hangUpDeferred = false

    /** Responses that proposed a phone action, so a hang-up in the same breath waits for its result. */
    private val actionResponses = mutableSetOf<String>()
    private var assistantSpeaking = false

    /**
     * An action that ends the call after the model's reply succeeded. A response ends only once no
     * action is left to report, so the next end is that reply's, whatever else it proposed first.
     */
    private var replyHangUp = false

    /** A turn served by an action that ends the call, whose held result the model will not answer. */
    private var servedByAction: TurnTask? = null

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
        /** Actions whose success ends this call, as decided when it opened; never on a text leg. */
        val endings: Map<String, CallEnding> = emptyMap(),
    )

    private val connectionTools = mutableMapOf<ConversationSession, ConnectionTools>()

    /** Persistent provider notices waiting for their session's start notice; absent once it is written. */
    private val heldNotices = mutableMapOf<ConversationSession, MutableList<String>>()

    /**
     * Read when a session opens, not once at construction, so switching a capability off takes
     * effect on the next connection. A live session keeps the catalog it was opened with.
     */
    private fun phoneTools(
        snapshot: CapabilityRegistry.Snapshot,
        voice: Boolean,
        promptHidden: Set<String>,
        textControls: Int = 0,
    ): Pair<com.colonelpanic.eva.capability.CatalogAdmission.Selection, List<ProviderToolDefinition>> {
        // Prompt-hidden tools leave before admission so they never take capacity from offered ones.
        val noSkills = skills().isEmpty()
        val offered =
            snapshot.catalog.filterNot {
                it.id in hiddenCapabilities() || it.id in promptHidden || (noSkills && it.id == SkillCapabilities.USE)
            }
        val controls =
            if (voice) {
                com.colonelpanic.eva.capability.CatalogAdmission
                    .voiceControls(offered)
            } else {
                textControls
            }
        val selection =
            com.colonelpanic.eva.capability.CatalogAdmission
                .select(offered, controls)
        return selection to
            selection.admitted.map { definition ->
                val tool = ProviderToolDefinition(definition.id, definition.title, definition.modelDescription(), definition.inputSchema)
                // An extension's own words are untrusted data; only EVA's tools take followed wording.
                if (definition.source == null) wording().describe(tool) else tool
            }
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
        val unavailable =
            if (catalog.excludedTools.isEmpty()) {
                ""
            } else {
                "\n\n" +
                    wording()
                        .message(Wording.CATALOG_UNAVAILABLE)
                        .replace("{count}", catalog.excludedTools.size.toString())
                        .replace("{names}", catalog.excludedNames(ProviderToolCatalog.NOTE_NAMES, quoted = true))
            }
        return unavailable +
            if (notes.isEmpty()) {
                ""
            } else {
                "\n\n" + wording().message(Wording.EXTENSION_GUIDANCE) + "\n" + JsonArray(notes)
            }
    }

    private fun callEndings(snapshot: CapabilityRegistry.Snapshot): Map<String, CallEnding> {
        val overrides = callEndings()
        return snapshot.catalog
            .associate { it.id to (overrides[it.id] ?: it.endsVoiceCall) }
            .filterValues { it != CallEnding.NEVER }
    }

    /** EVA's own note, outside any extension's quoted metadata, so the model words its reply for the hang-up. */
    private fun endingNote(
        tool: ProviderToolDefinition,
        ending: CallEnding?,
    ): ProviderToolDefinition {
        val key =
            when (ending) {
                CallEnding.IMMEDIATELY -> Wording.ENDS_CALL_IMMEDIATELY
                CallEnding.AFTER_REPLY -> Wording.ENDS_CALL_AFTER_REPLY
                CallEnding.NEVER, null -> return tool
            }
        return tool.copy(description = tool.description + "\n\n" + wording().message(key))
    }

    /**
     * Which bridge services exist is configuration, not wording, so EVA's note names them on the
     * messaging tools at connection time; the note's words still come from the followed wording.
     */
    private fun bridgeNote(tool: ProviderToolDefinition): ProviderToolDefinition {
        if (tool.capabilityId !in MESSAGING_TOOLS) return tool
        val bridges = messagingBridges().takeIf { it.isNotEmpty() } ?: return tool
        val listed = bridges.entries.joinToString(", ") { (name, label) -> if (label.equals(name, true)) name else "$name ($label)" }
        return tool.copy(
            description =
                tool.description + "\n\n" + wording().message(Wording.MESSAGING_BRIDGES).replace("{services}", listed),
        )
    }

    /** Like the bridge note: which skills exist is configuration, named on the skill tool in followed wording. */
    private fun skillNote(tool: ProviderToolDefinition): ProviderToolDefinition {
        if (tool.capabilityId != SkillCapabilities.USE) return tool
        val catalog = SkillCapabilities.catalog(skills()).takeIf { it.isNotEmpty() } ?: return tool
        return tool.copy(description = tool.description + "\n\n" + wording().message(Wording.SKILLS) + "\n" + catalog)
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
            deviceTasks?.lease?.ownerFlow?.collect { callId ->
                tasks.values.firstOrNull { callId in it.actionCallIds }?.let { taskProgress(it.turnId) }
                refreshTaskSnapshots()
            }
        }
        scope.launch { deviceTasks?.waitingForLease?.collect { refreshTaskSnapshots() } }
        scope.launch { deviceTasks?.releasing?.collect { refreshTaskSnapshots() } }
        scope.launch {
            deviceTasks?.running?.collect { running ->
                syncDeviceQuestion()
                running?.let { taskProgress(it.turnId) }
                refreshTaskSnapshots()
                val owned = running?.takeIf { it.threadId == shownThreadId }
                mutableState.update {
                    it.copy(
                        entries = it.entries.withLiveSteps(owned),
                        deviceTaskActive = owned != null,
                        deviceTaskProgress = owned?.progress?.let { progress -> progress.message ?: progressLabel(progress.phase) },
                    )
                }
            }
        }
    }

    init {
        scope.launch {
            try {
                repository.recoverInterrupted()
                store.recoverInterrupted().forEach { turn ->
                    store
                        .items(turn.threadId, limit = Int.MAX_VALUE)
                        .filterIsInstance<ThreadItem.Question>()
                        .filter { it.turnId == turn.id }
                        .associateBy { it.evidence.questionId }
                        .values
                        .filter { it.evidence.waiting }
                        .forEach {
                            store.append(
                                it.copy(
                                    id = UUID.randomUUID().toString(),
                                    createdAtMillis = nowMillis(),
                                    evidence = it.evidence.copy(resolution = QuestionResolution.INTERRUPTED),
                                ),
                            )
                        }
                    store.append(notice(turn.threadId, turn.id, NoticeKind.INTERRUPTED, "EVA closed before this request finished."))
                }
                shownThreadId = store.threads().firstOrNull()?.id
                refresh()
                mutableState.update { it.copy(isLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (error is UnsupportedJournalVersionException) error.message else SessionController.STORAGE_ERROR
                mutableState.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
        scope.launch { store.changes.collect { requestRefresh() } }
        scope.launch {
            for (request in refreshRequests) {
                try {
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                }
            }
        }
    }

    private fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    // ---- threads ----

    /** Starts an empty thread and shows it. The next attachment or request lands there. */
    fun newThread() {
        scope.launch {
            val thread = store.createThread(UNTITLED)
            shownThreadId = thread.id
            reattachTextToShownThread()
            refresh()
        }
    }

    fun showThread(id: String) {
        shownThreadId = id
        reattachTextToShownThread()
        scope.launch { refresh() }
    }

    private fun reattachTextToShownThread() {
        val current = state.value
        if (!current.voiceMode && current.providerStatus != ProviderStatus.DISCONNECTED && shownThreadId != attachedThreadId) {
            lastTextLink?.let { connectSession(it, voice = false, endReason = "ended: switched conversations") }
        }
    }

    private suspend fun shownOrNewThread(): String = shownThreadId ?: store.createThread(UNTITLED).id.also { shownThreadId = it }

    private suspend fun refresh() = refreshLock.withLock { load() }

    private suspend fun load() {
        mutableState.update {
            it.copy(
                currentQuestion = currentQuestion(shownThreadId)?.evidence,
                pendingQuestionCount = pendingQuestions(shownThreadId).size,
                voiceQuestion = currentQuestion(attachedThreadId)?.evidence?.question,
            )
        }
        val id = shownThreadId
        val summaries =
            store.threads().map { ThreadSummary(it.id, it.title, it.updatedAtMillis, hasActiveTasks(it.id)) }
        mutableThreads.value = summaries
        mutableWorking.value =
            tasks.values
                .filter { it.active }
                .map { it.threadId }
                .toSet()
        if (id == null) {
            mutableState.update {
                it.copy(threadId = null, entries = emptyList(), working = false, foregroundWorking = false, deviceTaskActive = false)
            }
            return
        }
        // Every turn is listed, so every item is too; a window would strip older turns of their actions.
        val items = store.items(id, limit = Int.MAX_VALUE)
        val entries = projectEntries(store.turns(id), items, receipts(items)).withLiveSteps(deviceTasks?.running?.value)
        mutableState.update {
            it.copy(
                threadId = id,
                entries = entries,
                working = hasActiveTasks(id),
                foregroundWorking = foregroundTask(id) != null,
                deviceTaskActive =
                    deviceTasks?.running?.value?.threadId == id,
            )
        }
    }

    private fun List<ConversationEntry>.withLiveSteps(running: com.colonelpanic.eva.devicecontrol.DeviceTaskCoordinator.Running?) =
        withLiveSteps(
            running?.callId,
            running?.steps.orEmpty().map { DeviceStep(it.step, it.kind, it.detail, it.result, it.intent, it.backend) },
        )

    private suspend fun receipts(items: List<ThreadItem>): Map<String, InvocationRecord> {
        val wanted = items.filterIsInstance<ThreadItem.ActionCall>().map { it.callId }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        return repository.byCallIds(wanted)
    }

    private fun hasActiveTasks(threadId: String): Boolean = tasks.values.any { it.threadId == threadId && it.active }

    private fun foregroundTask(threadId: String): TurnTask? =
        tasks.values.lastOrNull { it.threadId == threadId && it.active && !it.delegated && it.leg === session && session != null }

    private fun attachedTask(leg: ConversationSession): TurnTask? = tasks.values.lastOrNull { it.active && !it.delegated && it.leg === leg }

    /**
     * Provider input ids are leg-local: a realtime session numbers its turns from scratch, so
     * two sessions on one thread would collide. Turn identity is the store's, and a leg's ids
     * are resolved against the leg that issued them.
     */
    private fun taskFor(
        leg: ConversationSession,
        inputId: String,
    ): TurnTask? = tasks.values.firstOrNull { it.active && !it.delegated && it.leg === leg && it.inputId == inputId }

    private fun notice(
        threadId: String,
        turnId: String?,
        kind: NoticeKind,
        text: String,
    ) = ThreadItem.Notice(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), kind, text)

    // ---- attachment ----

    fun connect(link: String) {
        lastTextLink = link
        connectSession(link, voice = false)
    }

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
        endReason: String = ENDED_FOR_NEW_SESSION,
    ) {
        if (state.value.isLoading || state.value.errorMessage != null) return
        end(endReason)
        EvaTrace.info("session.connecting", "voice" to voice, "newThread" to newThread, "broker" to link.isNotBlank())
        val thisAttempt = attempt
        mutableState.update { it.copy(providerStatus = ProviderStatus.CONNECTING, providerMessage = null, voiceMode = voice) }
        connectionJob =
            scope.launch {
                var openedSession: ConversationSession? = null
                var threadId: String? = null
                try {
                    threadId = if (newThread) store.createThread(UNTITLED).id.also { shownThreadId = it } else shownOrNewThread()
                    attachedThreadId = threadId
                    mutableState.update { it.copy(attachedThreadId = threadId) }
                    refresh()
                    // Assembled before any provider work so a broken file fails here, with its message.
                    awaitCapabilities()
                    val assembled = assemble(voice, callMode)
                    // Only a spoken session is something the model can hang up. The catalog is built per
                    // connection because a switched-off capability and the enabled components both decide
                    // which tools are offered and what they say.
                    val snapshot = registry.snapshot
                    val endings = if (voice) callEndings(snapshot) else emptyMap()
                    val phone = phoneTools(snapshot, voice, assembled.hidden)
                    val deviceControls =
                        if (voice && phone.second.any { it.capabilityId == CapabilityRegistry.DEVICE_TASK }) {
                            listOf(DEVICE_TASK_REVISE, DEVICE_TASK_STOP).map(wording()::describe)
                        } else {
                            emptyList()
                        }
                    val connectionCatalog =
                        catalogOf(
                            assembled
                                .apply(
                                    (
                                        if (voice) {
                                            listOf(
                                                END_CONVERSATION,
                                                DEFER_TO_TEXT,
                                                BACKGROUND_STATUS,
                                                BACKGROUND_CANCEL,
                                                BACKGROUND_ANSWER,
                                            ).map(wording()::describe)
                                        } else {
                                            emptyList()
                                        }
                                    ) +
                                        deviceControls + phone.second,
                                ).map { tool -> endingNote(skillNote(bridgeNote(tool)), endings[tool.capabilityId]) },
                            snapshot.revision,
                        ).copy(
                            excludedTools = phone.first.excluded(),
                        )
                    val provider =
                        if (!voice) {
                            providerFactory(link)
                        } else {
                            val audio = checkNotNull(mediaFactory).invoke()
                            media = audio
                            launch {
                                audio.controls.collect { controls ->
                                    if (thisAttempt == attempt) {
                                        mutableState.update { it.copy(mediaControls = controls) }
                                        if (controls.focus == AudioFocusState.LOST) hangUp(ENDED_AUDIO_TAKEN)
                                    }
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
                                history =
                                    projectHistory(
                                        items,
                                        receipts(items),
                                        questionHistoryNote = wording().message(Wording.BACKGROUND_QUESTION_HISTORY),
                                        readBounded =
                                            items.size >= ConversationStore.DEFAULT_ITEM_LIMIT,
                                    ),
                            ),
                        )
                    openedSession = opened
                    connectionTools[opened] = ConnectionTools(snapshot, connectionCatalog, voice, assembled.callMode, endings)
                    heldNotices[opened] = mutableListOf()
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
                    EvaTrace.info(
                        "session.closed",
                        "thread" to threadId,
                        "voice" to voice,
                        "opened" to (openedSession != null),
                        "reason" to reason,
                    )
                    if (openedSession != null && threadId != null) {
                        val label = "${if (voice) "Call" else "Session"} $reason"
                        withContext(NonCancellable) { store.append(notice(threadId, null, NoticeKind.SESSION_ENDED, label)) }
                    }
                    if (thisAttempt == attempt) {
                        session = null
                        media?.close()
                        media = null
                        attachedThreadId = null
                        submittingInputId = null
                        mutableState.update {
                            it.copy(
                                providerStatus = ProviderStatus.DISCONNECTED,
                                isSubmitting = false,
                                foregroundWorking = false,
                                attachedThreadId = null,
                                providerModel = null,
                                voiceMode = false,
                                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
                            )
                        }
                    }
                    threadId?.let { detach(it, openedSession) }
                    connectionTools.remove(openedSession)
                    heldNotices.remove(openedSession)
                    voiceTurns.keys.removeAll { it.first === openedSession }
                    pendingAnnouncements.keys.removeAll { it.first === openedSession }
                    transcriptItems.keys.removeAll { it.first === openedSession }
                    questionRelays.keys.removeAll { it.first === openedSession }
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
        providerProgress(event, opened)
        when (event) {
            is ProviderEvent.Connected -> {
                check(event.catalogRevision == connectionTools.getValue(opened).catalog.revision)
                val model =
                    listOfNotNull(event.model, event.backendModel)
                        .distinct()
                        .joinToString(" · ")
                        .ifBlank { null }
                val held = heldNotices.remove(opened).orEmpty()
                mutableState.update {
                    it.copy(providerStatus = ProviderStatus.CONNECTED, providerMessage = held.lastOrNull(), providerModel = model)
                }
                updateWorkCoverage()
                relayQuestions()
                val label = listOfNotNull(if (voice) "Voice session" else "Text session", model).joinToString(" · ")
                store.append(
                    notice(threadId, null, NoticeKind.SESSION_STARTED, connectionTools.getValue(opened).catalog.sessionNotice(label)),
                )
                held.forEach { store.append(notice(threadId, null, NoticeKind.SESSION_STARTED, it)) }
                store.recordOffered(
                    threadId,
                    null,
                    if (voice) SessionKind.VOICE else SessionKind.TEXT,
                    null,
                    model,
                    connectionTools.getValue(opened).catalog,
                    nowMillis(),
                )
            }

            is ProviderEvent.Account -> {
                mutableState.update { it.copy(providerLabel = event.label) }
            }

            is ProviderEvent.ResponseStarted -> {
                disarmQuietHangUp()
                if (taskFor(opened, event.inputId) == null && voice) {
                    startTask(threadId, event.inputId, "", opened, spoken = true, announceOnly = event.announceOnly)
                }
            }

            is ProviderEvent.Transcript -> {
                val task =
                    when {
                        event.inputId != null -> taskFor(opened, event.inputId)
                        event.itemId == null -> attachedTask(opened)
                        else -> null
                    }
                if (event.role == "user") {
                    val transcriptId = UUID.randomUUID().toString()
                    event.inputId?.let { transcriptItems[opened to it] = transcriptId }
                    val owner = event.itemId?.let { deviceSpeechOwners.remove(opened.connectionEpoch + ":" + it) }
                    // Other speech is the voice model's to route: it may revise or stop the task, relay an
                    // answer to its question, or ask for something else, which queues behind it.
                    if (owner != null) {
                        if (event.text.trim().lowercase() in setOf("stop", "cancel", "stop device task", "cancel device task") &&
                            deviceTasks?.owns(owner.turnId) == true
                        ) {
                            deviceTasks.stop(owner.turnId)
                            tasks[owner.turnId]?.interrupt(wording().message(Wording.TURN_STOP_REQUESTED))
                        } else if (owner.question != null && owner.question == pendingQuestionKey(owner.threadId)) {
                            answerQuestion(
                                owner.threadId,
                                owner.turnId,
                                owner.question,
                                event.text,
                                AnswerProvenance.SPOKEN_FALLBACK,
                                transcriptId,
                            )
                        }
                    }
                    task?.request = event.text
                    store.append(
                        ThreadItem.UserMessage(
                            transcriptId,
                            threadId,
                            task?.turnId ?: event.inputId?.let { voiceTurns[opened to it]?.turnId },
                            nowMillis(),
                            event.text,
                            spoken = true,
                        ),
                    )
                    titleFrom(threadId, event.text)
                } else if (event.role == "assistant") {
                    if (task != null) {
                        task.answer(event.text, truncated = false, spoken = true)
                    } else {
                        store.append(
                            ThreadItem.AssistantMessage(
                                UUID.randomUUID().toString(),
                                threadId,
                                event.inputId?.let { voiceTurns[opened to it]?.turnId },
                                nowMillis(),
                                event.text,
                                spoken = true,
                            ),
                        )
                    }
                }
            }

            is ProviderEvent.ToolCallReady -> {
                check(event.call.catalogRevision == connectionTools.getValue(opened).catalog.revision)
                if (event.rejection != null) {
                    rejectUnowned(
                        event,
                        opened,
                        threadId,
                        voice,
                        when (event.rejection) {
                            CallRejection.INTERRUPTED_RESPONSE -> Wording.INTERRUPTED_RESPONSE_ACTION
                            CallRejection.INCOMPLETE_CALL -> Wording.INCOMPLETE_ACTION
                        },
                    )
                } else if (event.call.initiator?.kind == InitiatorKind.UNKNOWN) {
                    rejectUnowned(event, opened, threadId, voice, Wording.UNKNOWN_ORIGIN)
                } else if (voiceTurns[opened to event.call.inputId]?.announceOnly == true ||
                    event.call.initiator?.kind == InitiatorKind.LIFECYCLE_NOTE_REPLY
                ) {
                    rejectUnowned(event, opened, threadId, voice, Wording.ANNOUNCEMENT_ACTION)
                } else if (voice && event.capabilityId == END_CONVERSATION.capabilityId) {
                    // Nothing runs on the phone and, unless the hang-up is deferred, no result is
                    // returned, so the model is not prompted to speak again. Its goodbye plays out first.
                    endCall(ENDED_BY_MODEL, event.call)
                } else if (voice && event.capabilityId == DEFER_TO_TEXT.capabilityId) {
                    actionResponses += event.call.generationId
                    val task = taskFor(opened, event.call.inputId)
                    val instruction = (event.arguments["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    if (task == null ||
                        connectionTools[opened]?.catalog?.tools?.any { it.capabilityId == DEFER_TO_TEXT.capabilityId } != true ||
                        event.arguments.size != 1 || instruction.isNullOrEmpty()
                    ) {
                        opened.submitToolResult(
                            CorrelatedToolResult(
                                event.call,
                                "NOT_EXECUTED",
                                wording().message(Wording.HANDOFF_INVALID),
                            ),
                        )
                    } else {
                        task.delegateToText(instruction, event.call)
                    }
                } else if (voice && event.capabilityId in BACKGROUND_CONTROLS) {
                    controlBackgroundTask(event, opened, threadId)
                } else if (voice && event.capabilityId in DEVICE_CONTROLS) {
                    controlDeviceTask(event, opened, threadId)
                } else {
                    if (voice) actionResponses += event.call.generationId
                    val owner =
                        taskFor(opened, event.call.inputId) ?: tasks.values.firstOrNull {
                            it.active && it.delegated && it.originLeg === opened && it.originInputId == event.call.inputId
                        }
                    if (owner != null) {
                        owner.dispatch(event, source = opened, context = connectionTools.getValue(opened))
                    } else {
                        rejectUnowned(event, opened, threadId, voice)
                    }
                }
            }

            is ProviderEvent.SpeechInputStarted -> {
                val question = currentQuestion(threadId)
                val device = deviceTasks?.running?.value?.takeIf { it.threadId == threadId }
                val turnId = question?.evidence?.taskId ?: device?.turnId
                if (turnId != null) {
                    deviceSpeechOwners[opened.connectionEpoch + ":" + event.itemId] =
                        DeviceSpeech(
                            threadId,
                            turnId,
                            question?.evidence?.questionId?.takeUnless { questionHeld(opened) },
                        )
                }
            }

            is ProviderEvent.UserSpeaking -> {
                disarmQuietHangUp()
                // The user took the floor, so the call is no longer finished.
                replyHangUp = false
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
                val task = taskFor(opened, event.inputId)
                if (task != null) {
                    task.answer(event.text, event.truncated, voice)
                } else if (voice) {
                    store.append(
                        ThreadItem.AssistantMessage(
                            UUID.randomUUID().toString(),
                            threadId,
                            voiceTurns[opened to event.inputId]?.turnId,
                            nowMillis(),
                            event.text,
                            true,
                            event.truncated,
                        ),
                    )
                }
            }

            is ProviderEvent.ResponseEnded -> {
                val task = taskFor(opened, event.inputId)
                task?.generationEnded(event.status)
                if (voice && hangUpDeferred) {
                    // Another turn's result may still be due; the hang-up waits for its reply too.
                    if (!hasUnreportedWork(opened)) endCall(ENDED_BY_MODEL)
                } else if (voice && replyHangUp) {
                    endCall(ENDED_AFTER_ACTION)
                } else if (voice && task?.actionServiced == true && connectionTools[opened]?.callMode == VoiceCallMode.ONE_REQUEST &&
                    !hasUnreportedWork(opened) && pendingQuestionKey(threadId) == null
                ) {
                    // The model decides when a request is fully served, but one whose action is done and
                    // reported does not stay open just because it forgot to hang up: silence ends it.
                    quietArmed = quietHangUpMillis() > 0
                    if (quietArmed && !assistantSpeaking) startQuietTimer()
                }
            }

            is ProviderEvent.Notice -> {
                mutableState.update { it.copy(providerMessage = event.message) }
                if (event.persistent) {
                    heldNotices[opened]?.add(event.message)
                        ?: store.append(notice(threadId, null, NoticeKind.SESSION_STARTED, event.message))
                }
            }

            is ProviderEvent.ContextDelivery -> {
                event.ids.forEach { id ->
                    pendingAnnouncements.remove(opened to id)?.let { answer ->
                        if (event.delivered) onBackgroundAnswerDelivered(answer)
                    }
                    val relay =
                        questionRelays.entries
                            .firstOrNull { it.key.first === opened && QUESTION_DELIVERY + it.key.second == id }
                            ?.value
                    if (!event.delivered && relay != null && id == QUESTION_DELIVERY + relay.key) {
                        questionRelays[opened to relay.key] = relay.copy(held = false, failed = true)
                        EvaTrace.info("question.delivery_failed", "questionId" to relay.key)
                    }
                }
            }

            is ProviderEvent.Failure -> {
                throw IllegalStateException(event.message)
            }

            ProviderEvent.Closed -> {}
        }
    }

    private fun pendingQuestions(threadId: String?) = questions.values.filter { it.threadId == threadId && it.evidence.waiting }

    private fun currentQuestion(threadId: String?) = pendingQuestions(threadId).firstOrNull()

    private fun pendingQuestionKey(threadId: String?) = currentQuestion(threadId)?.evidence?.questionId

    private suspend fun recordQuestion(
        question: PendingQuestion,
        evidence: QuestionEvidence = question.evidence,
    ) {
        questionWrites.withLock {
            store.append(ThreadItem.Question(UUID.randomUUID().toString(), question.threadId, evidence.taskId, nowMillis(), evidence))
        }
        requestRefresh()
    }

    private fun questionsChanged() {
        mutableState.update {
            it.copy(
                currentQuestion = currentQuestion(shownThreadId)?.evidence,
                pendingQuestionCount = pendingQuestions(shownThreadId).size,
                voiceQuestion = currentQuestion(attachedThreadId)?.evidence?.question,
            )
        }
        if (pendingQuestionKey(attachedThreadId) != null) disarmQuietHangUp()
        refreshTaskSnapshots()
        relayQuestions()
        requestRefresh()
    }

    private fun syncDeviceQuestion() {
        val running = deviceTasks?.running?.value
        val progress = running?.progress?.takeIf { it.phase == TaskPhase.NEEDS_INPUT && !it.message.isNullOrBlank() }
        val key = progress?.let { "${it.taskId}:${it.step}" }
        questions.values.filter { it.evidence.source == QuestionSource.DEVICE_TASK && it.evidence.waiting && it.deviceKey != key }.forEach {
            resolveQuestion(it, QuestionResolution.CANCELLED)
        }
        if (running != null && progress != null && key != null && tasks[running.turnId]?.active == true &&
            !running.agent.isStopped && progress.revision == running.agent.revision
        ) {
            val id = "device:$key"
            if (id !in questions) {
                val question =
                    PendingQuestion(
                        running.threadId,
                        QuestionEvidence(
                            id,
                            running.turnId,
                            tasks[running.turnId]?.textLegId,
                            QuestionSource.DEVICE_TASK,
                            checkNotNull(progress.message),
                            order = nextQuestionOrder++,
                        ),
                        deviceKey = key,
                    )
                questions[id] = question
                EvaTrace.info(
                    "question.asked",
                    "questionId" to id,
                    "task" to question.evidence.taskId,
                    "source" to question.evidence.source,
                )
                val evidence = question.evidence
                val owner = checkNotNull(tasks[running.turnId])
                launchFinalization(owner) {
                    try {
                        recordQuestion(question, evidence)
                    } catch (_: Exception) {
                        mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                        owner.interrupt(SessionController.STORAGE_ERROR)
                    }
                }
            }
        }
        questionsChanged()
    }

    private fun resolveQuestion(
        question: PendingQuestion,
        resolution: QuestionResolution,
    ) {
        if (!question.evidence.waiting) return
        question.evidence = question.evidence.copy(resolution = resolution)
        val evidence = question.evidence
        val owner = tasks[evidence.taskId]
        EvaTrace.info("question.resolved", "questionId" to evidence.questionId, "resolution" to resolution)
        launchFinalization(owner) {
            try {
                recordQuestion(question, evidence)
            } catch (_: Exception) {
                mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                owner?.interrupt(SessionController.STORAGE_ERROR)
                return@launchFinalization
            }
            session?.takeIf { attachedThreadId == question.threadId && connectionTools[it]?.voice == true }?.let { voice ->
                runCatching { voice.submitContext(wording().message(Wording.BACKGROUND_QUESTION_RESOLVED), false, evidence.data()) }
            }
        }
    }

    private fun cancelQuestions(turnId: String) {
        val pending = questions.values.filter { it.evidence.taskId == turnId && it.evidence.waiting }
        if (pending.isEmpty()) return
        pending.forEach { resolveQuestion(it, QuestionResolution.CANCELLED) }
        questionsChanged()
    }

    private suspend fun answerQuestion(
        threadId: String,
        taskId: String,
        questionId: String,
        answer: String,
        provenance: AnswerProvenance,
        transcriptItemId: String? = null,
    ): String {
        var result = "cancelled"
        try {
            result = consumeQuestion(threadId, taskId, questionId, answer, provenance, transcriptItemId)
            return result
        } finally {
            EvaTrace.info("question.answer_attempt", "questionId" to questionId, "provenance" to provenance, "result" to result)
        }
    }

    private suspend fun consumeQuestion(
        threadId: String,
        taskId: String,
        questionId: String,
        answer: String,
        provenance: AnswerProvenance,
        transcriptItemId: String? = null,
    ): String {
        val question =
            questions[questionId]?.takeIf { it.threadId == threadId && it.evidence.taskId == taskId }
                ?: return Wording.BACKGROUND_ANSWER_MISSING
        if (answer.isBlank()) return Wording.BACKGROUND_ANSWER_INVALID
        if (question.evidence.resolution != QuestionResolution.PENDING ||
            tasks[taskId]?.active != true
        ) {
            return Wording.BACKGROUND_ANSWER_STALE
        }
        question.evidence =
            question.evidence.copy(
                resolution = QuestionResolution.SUBMITTING,
                answer = answer,
                provenance = provenance,
                transcriptItemId = transcriptItemId,
            )
        try {
            recordQuestion(question)
            if (question.evidence.resolution != QuestionResolution.SUBMITTING ||
                tasks[taskId]?.active != true
            ) {
                return Wording.BACKGROUND_ANSWER_STALE
            }
            val accepted =
                if (question.evidence.source == QuestionSource.DEVICE_TASK) {
                    deviceTasks?.answer(threadId, taskId, checkNotNull(question.deviceKey), answer) == true
                } else {
                    val target = checkNotNull(question.target)
                    if (tasks[taskId]?.leg !== target) {
                        false
                    } else {
                        target.submitToolResult(
                            CorrelatedToolResult(
                                checkNotNull(question.call),
                                "COMPLETED",
                                wording().message(Wording.BACKGROUND_ANSWER_RESULT),
                                question.evidence.data(),
                            ),
                        )
                        true
                    }
                }
            resolveQuestion(question, if (accepted) QuestionResolution.ACCEPTED else QuestionResolution.FAILED)
            questionsChanged()
            return if (accepted) Wording.BACKGROUND_ANSWER_ACCEPTED else Wording.BACKGROUND_ANSWER_STALE
        } catch (error: CancellationException) {
            resolveQuestion(question, QuestionResolution.CANCELLED)
            questionsChanged()
            throw error
        } catch (_: Exception) {
            resolveQuestion(question, QuestionResolution.FAILED)
            tasks[taskId]?.questionFailed()
            questionsChanged()
            return Wording.BACKGROUND_ANSWER_FAILED
        }
    }

    private fun questionHeld(voice: ConversationSession): Boolean =
        pendingQuestionKey(attachedThreadId)?.let { questionRelays[voice to it]?.held } == true

    private fun relayQuestions() {
        val question = currentQuestion(attachedThreadId) ?: return
        if (question.evidence.resolution != QuestionResolution.PENDING) return
        val key = question.evidence.questionId
        val voice =
            session?.takeIf {
                connectionTools[it]?.voice == true &&
                    connectionTools[it]?.catalog?.tools?.any { tool -> tool.capabilityId == BACKGROUND_ANSWER.capabilityId } == true
            } ?: return
        val relayKey = voice to key
        val previous = questionRelays[relayKey]
        if (previous != null && (previous.held || previous.failed)) return
        questionRelays[relayKey] = QuestionRelay(key, held = true)
        scope.launch {
            if (session !== voice || currentQuestion(attachedThreadId) !== question || !question.evidence.waiting) return@launch
            val accepted =
                runCatching {
                    voice.submitContext(
                        wording().message(Wording.BACKGROUND_QUESTION),
                        true,
                        question.evidence.data(),
                        QUESTION_DELIVERY + key,
                    )
                }.getOrDefault(false)
            EvaTrace.info("question.relay_submitted", "questionId" to key, "accepted" to accepted)
            if (!accepted) questionRelays[relayKey]?.let { questionRelays[relayKey] = it.copy(held = false) }
        }
    }

    /**
     * Revises or stops a running device task on the voice model's judgment. Like the panel's
     * controls, this latches straight into the task instead of queuing behind its pending call.
     */
    private suspend fun controlDeviceTask(
        event: ProviderEvent.ToolCallReady,
        opened: ConversationSession,
        threadId: String,
    ) {
        val offered = connectionTools[opened]?.catalog?.tools?.any { it.capabilityId == event.capabilityId } == true
        val correction = (event.arguments["correction"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val owner = deviceTasks?.running?.value?.takeIf { it.threadId == threadId }
        val (status, key) =
            when {
                !offered || owner == null -> {
                    "NOT_EXECUTED" to Wording.DEVICE_TASK_NONE
                }

                event.capabilityId == DEVICE_TASK_STOP.capabilityId && deviceTasks.stopRunning(threadId) -> {
                    "COMPLETED" to Wording.DEVICE_TASK_STOPPING
                }

                event.capabilityId == DEVICE_TASK_STOP.capabilityId -> {
                    "NOT_EXECUTED" to Wording.DEVICE_TASK_NONE
                }

                correction.isNullOrEmpty() -> {
                    "NOT_EXECUTED" to Wording.DEVICE_TASK_INVALID
                }

                reviseOrAnswerDevice(threadId, correction, transcriptItems[opened to event.call.inputId]) -> {
                    "COMPLETED" to Wording.DEVICE_TASK_REVISED
                }

                else -> {
                    "NOT_EXECUTED" to Wording.DEVICE_TASK_NONE
                }
            }
        opened.submitToolResult(CorrelatedToolResult(event.call, status, wording().message(key)))
    }

    private suspend fun reviseOrAnswerDevice(
        threadId: String,
        correction: String,
        transcriptId: String?,
    ): Boolean {
        val question = pendingQuestions(threadId).firstOrNull { it.evidence.source == QuestionSource.DEVICE_TASK }
        return if (question != null) {
            answerQuestion(
                threadId,
                question.evidence.taskId,
                question.evidence.questionId,
                correction,
                AnswerProvenance.DEVICE_REVISION,
                transcriptId,
            ) == Wording.BACKGROUND_ANSWER_ACCEPTED
        } else {
            deviceTasks?.revise(threadId, correction) == true
        }
    }

    private suspend fun controlBackgroundTask(
        event: ProviderEvent.ToolCallReady,
        opened: ConversationSession,
        threadId: String,
    ) {
        val definition = connectionTools[opened]?.catalog?.tools?.find { it.capabilityId == event.capabilityId }
        if (definition == null || ToolSchema.error(definition.inputSchema, event.arguments) != null) {
            val message =
                if (event.capabilityId ==
                    BACKGROUND_ANSWER.capabilityId
                ) {
                    Wording.BACKGROUND_ANSWER_INVALID
                } else {
                    Wording.BACKGROUND_INVALID
                }
            opened.submitToolResult(CorrelatedToolResult(event.call, "NOT_EXECUTED", wording().message(message)))
            return
        }
        if (event.capabilityId == BACKGROUND_ANSWER.capabilityId) {
            val key =
                answerQuestion(
                    threadId,
                    event.arguments
                        .getValue("taskId")
                        .jsonPrimitive.content,
                    event.arguments
                        .getValue("questionId")
                        .jsonPrimitive.content,
                    event.arguments
                        .getValue("answer")
                        .jsonPrimitive.content,
                    AnswerProvenance.VOICE_MODEL,
                    transcriptItems[opened to event.call.inputId],
                )
            opened.submitToolResult(
                CorrelatedToolResult(
                    event.call,
                    if (key == Wording.BACKGROUND_ANSWER_ACCEPTED) "COMPLETED" else "NOT_EXECUTED",
                    wording().message(key),
                ),
            )
            return
        }
        if (event.capabilityId == BACKGROUND_STATUS.capabilityId) {
            refreshTaskSnapshots()
            val live = taskSnapshots.value.associateBy { it.taskId }
            val items = store.items(threadId, limit = BACKGROUND_STATUS_ITEMS)
            val legs = items.filterIsInstance<ThreadItem.TextLeg>().associateBy { it.turnId }
            val calls = items.filterIsInstance<ThreadItem.ActionCall>().groupBy { it.turnId }
            val active =
                tasks.values.filter {
                    it.threadId == threadId && it.turnId in live &&
                        (it.isBackground || it.delegated || deviceTasks?.owns(it.turnId) == true)
                }
            val records = receipts(items) + repository.byCallIds(active.flatMap { it.actionCallIds })
            val turns = store.turns(threadId)
            val selected =
                turns.filter { turn -> active.any { it.turnId == turn.id } } +
                    turns
                        .filter { it.status != TurnStatus.OPEN && it.id in legs && it.id !in live }
                        .takeLast(
                            BACKGROUND_RECENT_TASKS,
                        ).reversed()
            val summaries = mutableListOf<JsonObject>()
            var used = 256
            for (turn in selected) {
                val task = tasks[turn.id]
                val actions = (calls[turn.id].orEmpty().map { it.callId } + task?.actionCallIds.orEmpty()).distinct()
                val summary =
                    buildJsonObject {
                        put("taskId", turn.id)
                        put("task", clipped(live[turn.id]?.request ?: legs[turn.id]?.task ?: turn.request, 256))
                        put("state", live[turn.id]?.state?.name ?: turn.status.name)
                        put("looksStuck", live[turn.id]?.looksStuck ?: false)
                        put(
                            "questions",
                            JsonArray(pendingQuestions(threadId).filter { it.evidence.taskId == turn.id }.map { it.evidence.data() }),
                        )
                        put("actions", live[turn.id]?.actionCount ?: actions.size)
                        put("historyLimited", items.size == BACKGROUND_STATUS_ITEMS && task == null)
                        actions.lastOrNull()?.let { id -> records[id]?.let { put("lastActionStatus", it.status.name) } }
                        put(
                            "recentReceipts",
                            JsonArray(
                                actions.takeLast(3).mapNotNull { id ->
                                    records[id]?.let { record ->
                                        buildJsonObject {
                                            put("callId", clipped(record.callId, 200))
                                            put("capabilityId", clipped(record.capabilityId, 200))
                                            put("status", record.status.name)
                                            put("arguments", clipped(record.arguments?.toString().orEmpty(), 400))
                                            put("message", clipped(record.message, 600))
                                            put("contentTrust", "external")
                                        }
                                    }
                                },
                            ),
                        )
                    }
                val size = summary.toString().length + 1
                if (used + size > MODEL_RESULT_CHARS) break
                summaries += summary
                used += size
            }
            opened.submitToolResult(
                CorrelatedToolResult(
                    event.call,
                    "COMPLETED",
                    wording().message(Wording.BACKGROUND_STATUS),
                    buildJsonObject {
                        put("tasks", JsonArray(summaries))
                        put("tasksOmitted", selected.size - summaries.size)
                        put("historyLimited", items.size == BACKGROUND_STATUS_ITEMS)
                        put("recentFinishedLimit", BACKGROUND_RECENT_TASKS)
                    },
                ),
            )
        } else {
            val id = (event.arguments["taskId"] as JsonPrimitive).content
            val task =
                tasks[id]?.takeIf {
                    it.threadId == threadId && it.active && (it.delegated || it.isBackground || deviceTasks?.owns(it.turnId) == true)
                }
            if (task == null) {
                opened.submitToolResult(CorrelatedToolResult(event.call, "NOT_EXECUTED", wording().message(Wording.BACKGROUND_NONE)))
            } else {
                task.interrupt(wording().message(Wording.TURN_STOP_REQUESTED))
                opened.submitToolResult(
                    CorrelatedToolResult(
                        event.call,
                        "COMPLETED",
                        wording().message(Wording.BACKGROUND_STOPPING),
                        buildJsonObject { put("taskId", id) },
                    ),
                )
            }
        }
    }

    /**
     * An unowned call returns its existing receipt, or a journaled refusal if it was never seen.
     */
    private suspend fun rejectUnowned(
        event: ProviderEvent.ToolCallReady,
        opened: ConversationSession,
        threadId: String,
        voice: Boolean,
        messageKey: String = Wording.UNOWNED_ACTION,
    ) {
        val message = wording().message(messageKey)
        val id = "provider:${event.call.providerSessionId}:${event.call.callId}"
        val arguments = event.arguments.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }
        val snapshot = connectionTools[opened]?.snapshot ?: registry.snapshot
        val title = snapshot.definitions[event.capabilityId]?.title ?: event.capabilityId
        var record: InvocationRecord? = null
        try {
            if (repository.byCallIds(listOf(id))[id] == null) {
                store.append(
                    ThreadItem.ActionCall(
                        UUID.randomUUID().toString(),
                        threadId,
                        voiceTurns[opened to event.call.inputId]?.turnId,
                        nowMillis(),
                        id,
                        event.capabilityId,
                        title,
                        arguments,
                        initiator = event.call.initiator,
                    ),
                )
            }
            record =
                dispatcher.execute(
                    ToolProposal(
                        id,
                        event.capabilityId,
                        arguments,
                        VOICE_REQUEST,
                        catalogRevision = snapshot.revision,
                        threadId = threadId,
                        turnId = voiceTurns[opened to event.call.inputId]?.turnId,
                        interactionMode = if (voice) InteractionMode.VOICE else InteractionMode.TYPED,
                        initiator = event.call.initiator,
                    ),
                    message,
                )
        } catch (error: CancellationException) {
            throw error
        } catch (_: InvocationPersistenceException) {
            mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
        } catch (_: Exception) {
            // Without a receipt, an earlier execution cannot be ruled out.
        }
        requestRefresh()
        try {
            opened.submitToolResult(
                CorrelatedToolResult(
                    event.call,
                    record?.status?.name ?: InvocationStatus.UNKNOWN.name,
                    record?.message ?: wording().message(Wording.RECEIPT_UNAVAILABLE),
                    record?.data,
                    record?.provenance,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The leg is gone and has no one left to tell.
        }
    }

    private fun startQuietTimer() {
        quietHangUp?.cancel()
        val thisAttempt = attempt
        quietHangUp =
            scope.launch {
                delay(quietHangUpMillis())
                if (thisAttempt == attempt && quietArmed && !hasUnreportedWork(session) && pendingQuestionKey(attachedThreadId) == null) {
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
        val opened = session
        if (request != null && opened != null && (hasUnreportedWork(opened) || request.generationId in actionResponses)) {
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

    /** A turn on [opened] still has an action running or a result the model has not replied to. */
    private fun hasUnreportedWork(opened: ConversationSession?): Boolean =
        opened != null &&
            tasks.values.any {
                it.active && it.leg === opened && !it.delegated && (it.dispatches.any { job -> job.isActive } || it.awaitingFollowUp)
            }

    /** Ends the attachment only. Whatever the thread was doing keeps going, on another leg if it has to. */
    fun disconnect() = end(ENDED_BY_USER)

    private fun end(reason: String) {
        if (connectionJob?.isActive == true) endReasons.putIfAbsent(attempt, reason)
        attempt++
        ending = false
        endRequest = null
        hangUpDeferred = false
        replyHangUp = false
        servedByAction = null
        disarmQuietHangUp()
        actionResponses.clear()
        assistantSpeaking = false
        connectionJob?.cancel()
        media?.close()
        media = null
        session = null
        attachedThreadId = null
        submittingInputId = null
        mutableState.update {
            it.copy(
                providerStatus = ProviderStatus.DISCONNECTED,
                isSubmitting = false,
                foregroundWorking = false,
                attachedThreadId = null,
                providerModel = null,
                voiceMode = false,
                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
            )
        }
        updateWorkCoverage()
    }

    private fun hangUp(reason: String) {
        val task = session?.let(::attachedTask)
        // The model chose to end the call, so an answer it has already spoken is complete. So is a
        // turn whose action ended the call: its result is held back from the model on purpose.
        if (task != null && !task.delegated && task.dispatches.none { it.isActive } &&
            (!task.awaitingFollowUp || task === servedByAction)
        ) {
            task.complete()
        }
        end(reason)
        mutableHangUps.tryEmit(Unit)
    }

    /** An action that ends the call once the model has replied to its result succeeded on [leg]. */
    private fun hangUpAfterReply(leg: ConversationSession) {
        if (session === leg) replyHangUp = true
    }

    /** An action that ends the call right away succeeded on [leg], serving [task]. */
    private fun hangUpAfterAction(
        leg: ConversationSession,
        task: TurnTask,
    ): Boolean {
        if (session !== leg || ending) return false
        servedByAction = task
        endCall(ENDED_AFTER_ACTION)
        return true
    }

    /** Cancels the shown thread's turn task. Distinct from ending the call, which leaves it running. */
    fun stopDeviceTask() {
        val owner = deviceTasks?.running?.value?.takeIf { it.threadId == shownThreadId } ?: return
        deviceTasks.stop(owner.turnId)
        tasks[owner.turnId]?.interrupt(wording().message(Wording.TURN_STOP_REQUESTED))
    }

    fun reviseDeviceTask(text: String): Boolean {
        if (text.isBlank()) return false
        val thread = shownThreadId ?: return false
        val owner = deviceTasks?.running?.value?.takeIf { it.threadId == thread } ?: return false
        if (text.trim().lowercase() in setOf("stop", "cancel", "stop device task", "cancel device task")) {
            stopDeviceTask()
            return true
        }
        if (!deviceTasks.revise(thread, text)) return false
        scope.launch { store.append(ThreadItem.UserMessage(UUID.randomUUID().toString(), thread, owner.turnId, nowMillis(), text, false)) }
        return true
    }

    fun stopTask() {
        val threadId = shownThreadId ?: return
        val deviceOwner = deviceTasks?.running?.value?.takeIf { it.threadId == threadId }
        if (deviceOwner != null) {
            stopDeviceTask()
            return
        }
        val task = foregroundTask(threadId) ?: tasks.values.lastOrNull { it.threadId == threadId && it.active } ?: return
        task.interrupt(wording().message(Wording.TURN_STOP_REQUESTED))
    }

    fun voiceUnavailable(reason: String) {
        end(reason)
        mutableState.update { it.copy(providerMessage = reason) }
    }

    /** Rechecks attachment ownership when work-service coverage is lost. */
    fun interruptBackgroundWork(reason: String) {
        (tasks.values + journalJobs.values)
            .distinct()
            .filter { !voiceCovered(it) }
            .forEach {
                it.coverageLost = true
                it.interrupt(reason)
            }
        updateWorkCoverage()
    }

    /** Every running task is interrupted with [reason], for when Android will not let them continue. */
    fun interruptAll(reason: String) {
        deviceTasks?.stop()
        tasks.values.filter { it.active }.forEach { it.interrupt(reason) }
    }

    /**
     * Interrupts every running turn and returns once each turn's work has ended, including its
     * dispatched actions and final journal writes. A host calls this before closing the storage the
     * controller writes to; turns run outside [scope], so cancelling it does not stop them.
     */
    suspend fun drain(reason: String) {
        interruptAll(reason)
        turnJobs.toList().joinAll()
    }

    fun submitAnswer(
        questionId: String,
        text: String,
    ) {
        if (state.value.voiceOnAnotherThread || text.isBlank()) return
        val threadId = shownThreadId ?: return
        val question = questions[questionId]
        if (question?.threadId == threadId && question.evidence.source == QuestionSource.DEVICE_TASK &&
            text.trim().lowercase() in setOf("stop", "cancel", "stop device task", "cancel device task") &&
            question.evidence.resolution == QuestionResolution.PENDING && deviceTasks?.owns(question.evidence.taskId) == true
        ) {
            stopDeviceTask()
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val key = answerQuestion(threadId, question?.evidence?.taskId.orEmpty(), questionId, text, AnswerProvenance.TYPED)
            if (key != Wording.BACKGROUND_ANSWER_ACCEPTED) mutableState.update { it.copy(providerMessage = wording().message(key)) }
        }
    }

    fun submit(text: String) {
        if (state.value.voiceOnAnotherThread) return
        if (text.trim().lowercase() in setOf("stop", "cancel", "stop device task", "cancel device task") &&
            deviceTasks?.running?.value?.threadId == shownThreadId && reviseDeviceTask(text)
        ) {
            return
        }
        val question = currentQuestion(shownThreadId)
        if (text.isNotBlank() && question != null) {
            submitAnswer(question.evidence.questionId, text)
            return
        }
        if (text.isNotBlank() && reviseDeviceTask(text)) return
        val current = state.value
        val opened = session ?: return
        val threadId = attachedThreadId ?: return
        if (current.isLoading || current.errorMessage != null || current.isSubmitting || threadId != shownThreadId ||
            current.providerStatus != ProviderStatus.CONNECTED || current.voiceMode ||
            text.isBlank()
        ) {
            return
        }
        if (foregroundTask(threadId) != null) {
            mutableState.update { it.copy(providerMessage = "EVA is still working on the last request.") }
            return
        }
        val input = ConversationInput(UUID.randomUUID().toString(), text)
        submittingInputId = input.id
        val thisAttempt = attempt
        mutableState.update { it.copy(isSubmitting = true, providerMessage = null) }
        scope.launch {
            try {
                if (thisAttempt != attempt || session !== opened || attachedThreadId != threadId) return@launch
                val task = startTask(threadId, input.id, text, opened, spoken = false)
                if (!task.active) return@launch
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
            } finally {
                finishSubmitting(input.id)
            }
        }
    }

    private fun finishSubmitting(inputId: String) {
        if (submittingInputId != inputId) return
        submittingInputId = null
        mutableState.update { it.copy(isSubmitting = false) }
    }

    private suspend fun startTask(
        threadId: String,
        inputId: String,
        request: String,
        leg: ConversationSession,
        spoken: Boolean,
        announceOnly: Boolean = false,
    ): TurnTask {
        val turnId = UUID.randomUUID().toString()
        store.openTurn(threadId, request, turnId)
        if (request.isNotBlank()) {
            store.append(ThreadItem.UserMessage(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), request, spoken))
            titleFrom(threadId, request)
        }
        val task = TurnTask(threadId, turnId, inputId, request, leg, announceOnly)
        tasks[turnId] = task
        progress[turnId] = TaskProgressRecord(nowMillis())
        if (spoken) voiceTurns[leg to inputId] = VoiceTurn(turnId, announceOnly)
        finishSubmitting(inputId)
        mutableState.update {
            it.copy(
                working = shownThreadId?.let(::hasActiveTasks) == true,
                foregroundWorking = shownThreadId?.let(::foregroundTask) != null,
            )
        }
        mutableWorking.update { it + threadId }
        updateWorkCoverage()
        if (!spoken) onWorkAccepted()
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
        val announceOnly: Boolean,
    ) {
        val taskScope =
            CoroutineScope(scope.coroutineContext + SupervisorJob()).also { taskScope ->
                val job = taskScope.coroutineContext.job
                turnJobs += job
                job.invokeOnCompletion { turnJobs -= job }
            }
        var leg: ConversationSession? = leg
            private set
        val originLeg = leg
        val originInputId = inputId
        var textLegId: String? = null
            private set
        private var backgroundSessionId: String? = null
        private var background: ConversationSession? = null
        private val backgroundReady = CompletableDeferred<Boolean>()
        var backgroundState = "CONNECTING"
            private set
        private var tools = connectionTools.getValue(leg)
        var awaitingFollowUp = false
            private set
        val dispatches = mutableListOf<Job>()

        /** Dispatches serialized on the device lease, which a handoff does not wait for. */
        private val deviceDispatches = mutableSetOf<Job>()
        private val mutationDispatches = mutableSetOf<Job>()

        /** This request's phone action completed or was handed off, so the request has been served. */
        var actionServiced = false
            private set
        private val dispatchLock = Mutex()
        var coverageLost = false
        private val mutationLock = mutationLocks.getOrPut(threadId) { Mutex() }
        val actionCallIds = linkedSetOf<String>()

        /** Every call proposed for this turn, so an ending action can tell whether it was proposed alone. */
        private val proposedCalls = mutableListOf<CallIdentity>()
        private val deliveredCalls = mutableSetOf<CallIdentity>()
        private var mutationUncertain = false
        private var rehomed = false
        private var forceStopReason: String? = null
        private val forceRequested = CompletableDeferred<Unit>()
        private val forceEscalated = CompletableDeferred<Unit>()
        private var answered = false
        private var forceStopJournal: Job? = null
        private val terminalWrites = Mutex()
        private var detached = false
        val isBackground: Boolean get() = rehomed || detached
        var delegated = false
            private set
        private val backgroundAnswers = mutableListOf<String>()
        private val answerParts = mutableListOf<String>()
        var active = true
            private set

        fun questionFailed() = fail(wording().message(Wording.BACKGROUND_ANSWER_FAILED))

        private suspend fun askUser(
            event: ProviderEvent.ToolCallReady,
            opened: ConversationSession,
            legId: String,
        ) {
            val definition = tools.catalog.tools.find { it.capabilityId == ASK_USER.capabilityId }
            val id = "text:$turnId:${event.call.connectionEpoch}:${event.call.callId}"
            if (id in questions) return
            val text = (event.arguments["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!active || leg !== opened || event.call.connectionEpoch != opened.connectionEpoch ||
                event.call.inputId != inputId || event.call.providerSessionId != backgroundSessionId ||
                event.call.catalogRevision != tools.catalog.revision ||
                (
                    event.call.initiator != null &&
                        (event.call.initiator.kind != InitiatorKind.TEXT_AGENT || event.call.initiator.legId != legId)
                ) ||
                event.rejection != null || definition == null || ToolSchema.error(definition.inputSchema, event.arguments) != null ||
                text.isNullOrBlank()
            ) {
                opened.submitToolResult(CorrelatedToolResult(event.call, "NOT_EXECUTED", wording().message(Wording.BACKGROUND_ASK_INVALID)))
                return
            }
            val question =
                PendingQuestion(
                    threadId,
                    QuestionEvidence(id, turnId, legId, QuestionSource.TEXT_AGENT, text, order = nextQuestionOrder++),
                    opened,
                    event.call,
                )
            questions[id] = question
            EvaTrace.info(
                "question.asked",
                "questionId" to id,
                "task" to question.evidence.taskId,
                "source" to question.evidence.source,
            )
            recordQuestion(question)
            questionsChanged()
        }

        fun dispatch(
            event: ProviderEvent.ToolCallReady,
            legId: String? = null,
            source: ConversationSession? = leg,
            context: ConnectionTools = tools,
        ) {
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
            proposedCalls += event.call
            actionCallIds += id
            progress[turnId]?.apply {
                lastActionCallId = id
                lastActionTitle = definition?.title ?: event.capabilityId
                lastActionStatus = "DISPATCHING"
                waiting = false
            }
            taskProgress(turnId)
            val queueNotified =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            val queuedNotice: () -> Unit = {
                if (queueNotified.compareAndSet(false, true)) {
                    mutableState.update {
                        it.copy(
                            providerMessage = "${definition?.title ?: event.capabilityId} is queued behind earlier work.",
                        )
                    }
                    taskScope.launch {
                        runCatching {
                            source?.submitContext(
                                wording().message(Wording.ACTION_QUEUED),
                                respond = true,
                                data =
                                    buildJsonObject {
                                        put("callId", event.call.callId)
                                        put("taskId", turnId)
                                        put("state", "QUEUED")
                                        put("contentTrust", "external_data")
                                    },
                            )
                        }
                    }
                }
            }
            refreshTaskSnapshots()
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
                    initiator = event.call.initiator,
                    onWaiting = {
                        progress[turnId]?.waiting = true
                        refreshTaskSnapshots()
                        mutableState.update { it.copy(providerMessage = "Still waiting for the action…") }
                    },
                    onQueued = queuedNotice,
                )
            // A native tool that offers `quiet` lets the model skip the spoken follow-up to a completed call.
            val quiet =
                context.voice && definition?.source == null &&
                    (definition?.inputSchema?.get("properties") as? JsonObject)?.containsKey("quiet") == true &&
                    (event.arguments["quiet"] as? JsonPrimitive)?.booleanOrNull == true

            suspend fun run() {
                val rejection =
                    dispatchLock.withLock {
                        val rejection =
                            when {
                                !active -> {
                                    wording().message(Wording.TURN_STOPPED)
                                }

                                announceOnly -> {
                                    wording().message(Wording.ANNOUNCEMENT_ACTION)
                                }

                                event.call.catalogRevision != context.catalog.revision || definition == null ||
                                    context.catalog.tools.none {
                                        it.capabilityId == event.capabilityId
                                    } -> {
                                    "This action was not offered in this connection. Nothing was executed."
                                }

                                !definition.readOnly && !definition.bookkeeping &&
                                    (
                                        tasks.values.any { it.threadId == threadId && it.mutationUncertain } ||
                                            abandonedMutationOn(
                                                threadId,
                                            )
                                    ) -> {
                                    wording().message(Wording.MUTATION_UNCERTAIN)
                                }

                                else -> {
                                    ToolSchema.error(definition.inputSchema, event.arguments)
                                }
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
                                legId,
                                initiator = event.call.initiator,
                            ),
                        )
                        rejection
                    }
                // A long lookup in a voice turn would otherwise be silence until its receipt.
                val stillWorking =
                    if (context.voice && definition?.readOnly == true && rejection == null && argumentError == null) {
                        taskScope.launch {
                            delay(STILL_WORKING_NOTE_MILLIS)
                            runCatching {
                                source?.submitContext(
                                    wording().message(Wording.ACTION_STILL_WORKING),
                                    respond = true,
                                    data =
                                        buildJsonObject {
                                            put("callId", event.call.callId)
                                            put("taskId", turnId)
                                            put("state", "RUNNING")
                                            put("contentTrust", "external_data")
                                        },
                                )
                            }
                        }
                    } else {
                        null
                    }
                try {
                    taskProgress(turnId)
                    val result = dispatcher.execute(proposal, rejection ?: argumentError)
                    stillWorking?.cancel()
                    progress[turnId]?.takeIf { it.lastActionCallId == id }?.apply {
                        lastActionStatus = result.status.name
                        waiting = false
                    }
                    taskProgress(turnId)
                    refreshTaskSnapshots()
                    requestRefresh()
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
                    val succeeded = result.status == InvocationStatus.COMPLETED || result.status == InvocationStatus.HANDED_OFF
                    val ending = context.endings[event.capabilityId].takeIf { succeeded }
                    val delivered: suspend () -> Unit = {
                        deliver(
                            event.call,
                            source,
                            result.status.name,
                            result.message,
                            result.provenance,
                            result.data,
                            respond = !(quiet && result.status == InvocationStatus.COMPLETED),
                        )
                    }
                    when (ending) {
                        CallEnding.IMMEDIATELY -> {
                            endWith(event.call, delivered)
                        }

                        CallEnding.AFTER_REPLY -> {
                            delivered()
                            source?.let(::hangUpAfterReply)
                        }

                        else -> {
                            delivered()
                        }
                    }
                } catch (error: ProposalRejectedException) {
                    deliver(event.call, source, "NOT_EXECUTED", error.message.orEmpty())
                } catch (error: InvocationPersistenceException) {
                    mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                    interrupt("Action history could not be saved.")
                    end("ended: action history could not be saved")
                } finally {
                    stillWorking?.cancel()
                    // Still inside the thread lock, so a queued mutation sees this one as returned.
                    releaseAbandoned(threadId, currentCoroutineContext().job)
                }
            }
            // The device coordinator owns long tasks and UI serialization. Other mutations share the thread lock.
            val serializedByDevice =
                event.capabilityId == CapabilityRegistry.DEVICE_TASK ||
                    runCatching { context.snapshot.resolve(proposal)?.usesDeviceUi(proposal) == true }.getOrDefault(false)
            val job =
                taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        if (definition?.readOnly == true || serializedByDevice) {
                            run()
                        } else {
                            val waiting =
                                launch {
                                    delay(3_000)
                                    proposal.onQueued()
                                }
                            try {
                                mutationLock.withLock {
                                    waiting.cancel()
                                    run()
                                }
                            } finally {
                                waiting.cancel()
                            }
                        }
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            val record =
                                runCatching {
                                    repository.byCallIds(listOf(id))[id]
                                        ?: dispatcher.execute(proposal, wording().message(Wording.TURN_STOPPED))
                                }.getOrNull()
                            progress[turnId]?.takeIf { it.lastActionCallId == id }?.apply {
                                lastActionStatus = record?.status?.name ?: InvocationStatus.UNKNOWN.name
                                waiting = false
                            }
                            taskProgress(turnId)
                            deliver(
                                event.call,
                                source,
                                record?.status?.name ?: InvocationStatus.UNKNOWN.name,
                                record?.message ?: wording().message(Wording.RECEIPT_UNAVAILABLE),
                                record?.provenance,
                                record?.data,
                            )
                        }
                        throw error
                    }
                }
            dispatches += job
            if (serializedByDevice) deviceDispatches += job
            if (definition?.readOnly != true && definition?.bookkeeping != true) mutationDispatches += job
        }

        /**
         * Holds the result of an action that ends the call right away, so the model is not prompted
         * to speak over what the action started. Proposed alongside other actions, whose results the
         * model does have to report, it is delivered after all and the call ends after that reply.
         */
        private fun endWith(
            call: CallIdentity,
            deliverResult: suspend () -> Unit,
        ) {
            val voiceLeg = leg
            taskScope.launch {
                dispatches.toList().joinAll()
                val alone = proposedCalls.none { it != call && it.generationId == call.generationId }
                if (!alone || voiceLeg == null || !active || leg !== voiceLeg || !hangUpAfterAction(voiceLeg, this@TurnTask)) {
                    deliverResult()
                    if (!alone && voiceLeg != null) hangUpAfterReply(voiceLeg)
                }
            }
        }

        private suspend fun deliver(
            call: com.colonelpanic.eva.providers.CallIdentity,
            target: ConversationSession?,
            status: String,
            message: String,
            provenance: com.colonelpanic.eva.capability.ReceiptProvenance? = null,
            data: JsonObject? = null,
            respond: Boolean = true,
        ) {
            if (!deliveredCalls.add(call)) return
            val current = target
            if (current == null || call.connectionEpoch != current.connectionEpoch) {
                // The leg that asked is gone; the receipt reaches the next leg as evidence instead.
                return
            }
            try {
                current.submitToolResult(CorrelatedToolResult(call, status, message, data, provenance, respond))
                if (current === leg && !delegated) awaitingFollowUp = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is IOException || error is ClosedSendChannelException) {
                    if (current === leg && !delegated) legLost()
                } else {
                    // The session is still up; reopening the turn elsewhere would only hide the defect.
                    mutableState.update {
                        it.copy(
                            providerMessage =
                                "EVA could not return an action result to the conversation " +
                                    "(${error.message ?: error::class.simpleName}). The action's receipt is saved.",
                        )
                    }
                }
            }
        }

        suspend fun answer(
            text: String,
            truncated: Boolean,
            spoken: Boolean,
        ) {
            awaitingFollowUp = false
            answerParts += text
            if (background != null && !spoken) backgroundAnswers += text
            store.append(ThreadItem.AssistantMessage(UUID.randomUUID().toString(), threadId, turnId, nowMillis(), text, spoken, truncated))
        }

        /** Providers report this once per input, after any tool follow-up, so it is the answer's end. */
        fun generationEnded(status: String) {
            if (questions.values.any {
                    it.evidence.taskId == turnId && it.evidence.source == QuestionSource.TEXT_AGENT &&
                        it.evidence.waiting
                }
            ) {
                fail(wording().message(Wording.BACKGROUND_QUESTION_UNRESOLVED))
                return
            }
            if (deviceTasks?.owns(turnId) == true) return
            when {
                status == "cancelled" && rehomed -> interrupt(wording().message(Wording.BACKGROUND_RESPONSE_CANCELLED))
                status == "completed" || status == "cancelled" -> complete()
                else -> fail(wording().message(Wording.RESPONSE_FAILED))
            }
        }

        fun complete() {
            if (!active) return
            active = false
            cancelQuestions(turnId)
            answered = true
            refreshTaskSnapshots()
            launchFinalization(this) {
                awaitDispatches()
                forceStopJournal?.join()
                val status = TurnStatus.ANSWERED
                closeTurn(status)
                forceStopJournal?.join()
                reportBackground(status, forceStopReason)
                finish()
            }
        }

        private suspend fun awaitDispatches() {
            for (job in dispatches.toList()) {
                kotlinx.coroutines.selects.select<Unit> {
                    job.onJoin { }
                    forceRequested.onAwait { }
                }
                if (forceRequested.isCompleted) break
            }
            if (forceRequested.isCompleted) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    for (job in dispatches.toList()) {
                        kotlinx.coroutines.selects.select<Unit> {
                            job.onJoin { }
                            forceEscalated.onAwait { }
                        }
                        if (forceEscalated.isCompleted) break
                    }
                }
                if (dispatches.any { !it.isCompleted }) {
                    val stuck = mutationDispatches.filter { !it.isCompleted }
                    if (stuck.isNotEmpty()) {
                        synchronized(abandonedMutations) { abandonedMutations.getOrPut(threadId) { mutableSetOf() } += stuck }
                        stuck.forEach { job -> job.invokeOnCompletion { releaseAbandoned(threadId, job) } }
                    }
                    dispatcher.abandon(actionCallIds, wording().message(Wording.FORCE_STOP_ABANDONED))
                    progress[turnId]?.lastActionStatus = InvocationStatus.UNKNOWN.name
                    refreshTaskSnapshots()
                    turnJobs -= taskScope.coroutineContext.job
                }
            }
        }

        private suspend fun closeTurn(status: TurnStatus) =
            terminalWrites.withLock {
                EvaTrace.info(
                    "turn.closed",
                    "task" to turnId,
                    "status" to status,
                    "answered" to answered,
                    "forceStopped" to (forceStopReason != null),
                )
                store.closeTurn(
                    turnId,
                    if (answered) {
                        TurnStatus.ANSWERED
                    } else if (forceStopReason ==
                        null
                    ) {
                        status
                    } else {
                        TurnStatus.INTERRUPTED
                    },
                )
            }

        fun forceStop() {
            EvaTrace.info("task.force_stop", "task" to turnId, "escalated" to (forceStopReason != null))
            if (forceStopReason != null) {
                forceEscalated.complete(Unit)
                return
            }
            val reason = wording().message(Wording.TURN_FORCE_STOPPED)
            forceStopReason = reason
            forceRequested.complete(Unit)
            forceStopJournal =
                launchFinalization(this) {
                    closeTurn(TurnStatus.INTERRUPTED)
                    store.append(notice(threadId, turnId, NoticeKind.INTERRUPTED, interruptionText(reason)))
                    requestRefresh()
                }
            deviceTasks?.forceStop(turnId)
            if (active) interrupt(forceStopReason!!)
            taskScope.cancel()
            refreshTaskSnapshots()
        }

        fun interrupt(reason: String) {
            val drainingDevice = deviceTasks?.stop(turnId) == true
            if (!active) return
            EvaTrace.info("task.interrupted", "task" to turnId, "reason" to reason)
            active = false
            cancelQuestions(turnId)
            refreshTaskSnapshots()
            launchFinalization(this) {
                backgroundReady.complete(false)
                if (!drainingDevice) dispatches.forEach { it.cancel() }
                awaitDispatches()
                forceStopJournal?.join()
                closeTurn(TurnStatus.INTERRUPTED)
                if (forceStopReason == null) store.append(notice(threadId, turnId, NoticeKind.INTERRUPTED, interruptionText(reason)))
                reportBackground(TurnStatus.INTERRUPTED, forceStopReason ?: reason)
                finish()
            }
        }

        private fun fail(reason: String) {
            if (!active) return
            EvaTrace.info("task.failed", "task" to turnId, "rehomed" to rehomed, "delegated" to delegated, "reason" to reason)
            if (!rehomed && deviceTasks?.owns(turnId) == true) {
                legLost()
                return
            }
            active = false
            cancelQuestions(turnId)
            backgroundReady.complete(false)
            val drainingDevice = deviceTasks?.stop(turnId) == true
            launchFinalization(this) {
                if (!drainingDevice) dispatches.forEach { it.cancel() }
                awaitDispatches()
                forceStopJournal?.join()
                val status = if (forceStopReason == null) TurnStatus.FAILED else TurnStatus.INTERRUPTED
                closeTurn(status)
                if (forceStopReason == null) store.append(notice(threadId, turnId, NoticeKind.INTERRUPTED, interruptionText(reason)))
                reportBackground(status, forceStopReason ?: reason)
                finish()
            }
        }

        private suspend fun finish() {
            background?.let { runCatching { it.close() } }
            background = null
            leg = null
            tasks.remove(turnId)
            val resolved =
                questions.values
                    .filter { it.evidence.taskId == turnId && !it.evidence.waiting }
                    .map { it.evidence.questionId }
                    .toSet()
            questions.keys.removeAll(resolved)
            questionRelays.keys.removeAll { it.second in resolved }
            progress.remove(turnId)
            coverageNotices.removeAll { it.first == turnId }
            // An abandoned dispatch can still hold the lock; a fresh one would let the next mutation run beside it.
            if (tasks.values.none { it.threadId == threadId } && mutationLocks[threadId]?.isLocked != true) {
                mutationLocks.remove(threadId)
            }
            updateWorkCoverage()
            if (!hasActiveTasks(threadId)) mutableWorking.update { it - threadId }
            finishSubmitting(inputId)
            mutableState.update {
                it.copy(
                    working = shownThreadId?.let(::hasActiveTasks) == true,
                    foregroundWorking = shownThreadId?.let(::foregroundTask) != null,
                )
            }
            refresh()
            taskScope.cancel()
        }

        /** The leg this task was answering through is gone. Finish elsewhere once the phone's part settles. */
        fun legLost() {
            if (!active) return
            detached = true
            leg = null
            updateWorkCoverage()
            if (delegated) return
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
            if (!active || rehomed || delegated) return
            val voiceLeg = leg ?: return
            delegated = true
            EvaTrace.info("handoff.started", "task" to turnId, "call" to call.callId)
            updateWorkCoverage()
            proposedCalls += call
            request = instruction
            // Keep the proposing leg reachable until sibling calls have delivered their receipts.
            taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    onWorkAccepted()
                    dispatches.filter { it !in deviceDispatches }.joinAll()
                    if (active) rehome(instruction)
                } finally {
                    withContext(NonCancellable) {
                        val started = backgroundState == "WORKING"
                        runCatching {
                            voiceLeg.submitToolResult(
                                CorrelatedToolResult(
                                    call,
                                    if (started) "HANDED_OFF" else "NOT_EXECUTED",
                                    wording().message(if (started) Wording.HANDOFF_STARTED else Wording.HANDOFF_FAILED),
                                    buildJsonObject { put("taskId", turnId) },
                                ),
                            )
                        }
                    }
                }
            }
        }

        private fun interruptionText(reason: String): String =
            (listOf(reason) + answerParts).filter { it.isNotBlank() }.joinToString("\n\n")

        private suspend fun reportBackground(
            status: TurnStatus,
            reason: String? = null,
        ) {
            if ((!isBackground && !delegated) || (delegated && backgroundState != "WORKING")) return
            val answer = (if (status == TurnStatus.ANSWERED) backgroundAnswers else answerParts).joinToString("\n\n")
            val data =
                buildJsonObject {
                    put("taskId", turnId)
                    put("state", status.name)
                    put("task", request)
                    put("answer", boundedResultText(answer))
                    put("contentTrust", "external_data")
                    reason?.let { put("reason", it) }
                }
            val voice =
                session?.takeIf {
                    backgroundState == "WORKING" && attachedThreadId == threadId && connectionTools[it]?.voice == true
                }
            val notification = if (status == TurnStatus.ANSWERED) answer else interruptionText(reason.orEmpty())
            val fallback = BackgroundAnswer(threadId, store.thread(threadId)?.title ?: UNTITLED, notification, turnId, status)
            onBackgroundAnswer(fallback)
            if (voice != null) {
                pendingAnnouncements[voice to turnId] = fallback
                val accepted =
                    runCatching {
                        voice.submitContext(wording().message(Wording.BACKGROUND_UPDATE), respond = true, data = data, deliveryId = turnId)
                    }.getOrDefault(false)
                if (!accepted) pendingAnnouncements.remove(voice to turnId)
            }
        }

        private suspend fun rehome(instruction: String? = null): Boolean {
            if (rehomed) {
                fail(wording().message(Wording.BACKGROUND_RESTART_REFUSED))
                return false
            }
            rehomed = true
            inputId = turnId
            leg = null
            try {
                val started =
                    withTimeoutOrNull(BACKGROUND_START_TIMEOUT_MILLIS) {
                        val items = store.items(threadId)
                        awaitCapabilities()
                        val assembled = assemble(voice = false)
                        val snapshot = registry.snapshot
                        val phone = phoneTools(snapshot, false, assembled.hidden, textControls = 1)
                        val catalog =
                            catalogOf(assembled.apply(phone.second + wording().describe(ASK_USER)), snapshot.revision)
                                .copy(excludedTools = phone.first.excluded())
                        tools = ConnectionTools(snapshot, catalog, false)
                        val instructions =
                            assembled.instructions + extensionGuidance(snapshot, catalog) + "\n\n" +
                                (
                                    instruction?.let { wording().message(Wording.HANDOFF_INSTRUCTIONS) + "\n" + JsonPrimitive(it) }
                                        ?: wording().message(Wording.CONTINUATION)
                                )
                        val history =
                            projectHistory(
                                items,
                                receipts(items),
                                questionHistoryNote = wording().message(Wording.BACKGROUND_QUESTION_HISTORY),
                                readBounded =
                                    items.size >= ConversationStore.DEFAULT_ITEM_LIMIT,
                            )
                        val textLeg =
                            ThreadItem.TextLeg(
                                UUID.randomUUID().toString(),
                                threadId,
                                turnId,
                                nowMillis(),
                                instruction,
                                instructions,
                                history.size,
                            )
                        textLegId = textLeg.id
                        store.append(textLeg)
                        val opened =
                            backgroundProviderFactory().open(
                                SessionOpenRequest(
                                    instructions,
                                    catalog,
                                    history = history,
                                    continuation = Continuation(turnId, textLeg.id),
                                ),
                            )
                        background = opened
                        if (!active) {
                            opened.close()
                            background = null
                            return@withTimeoutOrNull false
                        }
                        currentCoroutineContext().ensureActive()
                        leg = opened
                        taskScope.launch {
                            try {
                                var connected = false
                                opened.events.takeWhile { it != ProviderEvent.Closed }.collect { event ->
                                    if (leg !== opened || !active) return@collect
                                    providerProgress(event, opened)
                                    when (event) {
                                        is ProviderEvent.Connected -> {
                                            check(event.catalogRevision == catalog.revision)
                                            if (!connected) {
                                                connected = true
                                                backgroundSessionId = event.sessionId
                                                store.recordOffered(
                                                    threadId,
                                                    turnId,
                                                    SessionKind.TEXT_LEG,
                                                    textLeg.id,
                                                    listOfNotNull(
                                                        event.model,
                                                        event.backendModel,
                                                    ).distinct().joinToString(" · ").ifBlank { null },
                                                    catalog,
                                                    nowMillis(),
                                                )
                                                if (catalog.excludedTools.isNotEmpty()) {
                                                    store.append(
                                                        notice(
                                                            threadId,
                                                            turnId,
                                                            NoticeKind.SESSION_STARTED,
                                                            catalog.sessionNotice("Background text session"),
                                                        ),
                                                    )
                                                }
                                                opened.requestResponse(ResponseRequest(turnId))
                                                backgroundState = "WORKING"
                                                EvaTrace.info(
                                                    "handoff.connected",
                                                    "task" to turnId,
                                                    "leg" to textLeg.id,
                                                    "delegated" to delegated,
                                                )
                                                taskProgress(turnId)
                                                backgroundReady.complete(true)
                                            }
                                        }

                                        is ProviderEvent.ToolCallReady -> {
                                            if (backgroundState == "WORKING" && event.call.inputId == turnId) {
                                                if (event.capabilityId == ASK_USER.capabilityId) {
                                                    askUser(event, opened, textLeg.id)
                                                } else {
                                                    dispatch(event, textLeg.id)
                                                }
                                            }
                                        }

                                        is ProviderEvent.AssistantText -> {
                                            if (event.inputId == turnId) answer(event.text, event.truncated, spoken = false)
                                        }

                                        is ProviderEvent.ResponseEnded -> {
                                            if (event.inputId == turnId && backgroundState == "WORKING") generationEnded(event.status)
                                        }

                                        is ProviderEvent.Failure -> {
                                            throw IllegalStateException(event.message)
                                        }

                                        else -> {}
                                    }
                                }
                                if (active) fail(wording().message(Wording.BACKGROUND_CONNECTION_ENDED))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                fail(error.message?.take(300) ?: wording().message(Wording.BACKGROUND_CONTINUATION_FAILED))
                            }
                        }
                        backgroundReady.await()
                    } == true
                if (!started && active) fail(wording().message(Wording.HANDOFF_TIMEOUT))
                return started
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error.message?.take(300) ?: wording().message(Wording.BACKGROUND_CONTINUATION_FAILED))
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
        /** The shared messaging tools a configured bridge extends. */
        val MESSAGING_TOOLS =
            setOf(CapabilityRegistry.CONVERSATIONS_SEARCH, CapabilityRegistry.CONVERSATION_READ, CapabilityRegistry.SMS_SEND)
        const val UNTITLED = "New conversation"
        private const val VOICE_REQUEST = "Voice request"
        private const val STILL_WORKING_NOTE_MILLIS = 8_000L

        /** Bounds the wait for a goodbye whose end is never reported. */
        private const val END_SPEECH_LIMIT_MILLIS = 10_000L
        private const val PLAYOUT_TAIL_MILLIS = 500L
        private const val ENDED_BY_USER = "ended by you"
        private const val ENDED_BY_MODEL = "ended by EVA with its end-call tool"
        private const val ENDED_AFTER_REQUEST = "ended by EVA: the request was done and the line went quiet"
        private const val ENDED_AFTER_ACTION = "ended by EVA after an action that hands the phone to something else"
        private const val ENDED_FOR_NEW_SESSION = "ended for a new session"
        private const val ENDED_AUDIO_TAKEN = "ended: another app took the audio"

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

        /** Control a running device task directly, bypassing the dispatcher like the panel's Stop. */
        val DEVICE_TASK_REVISE by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.device.task.revise",
                    "Correct the device task",
                    "",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"correction":{"type":"string","minLength":1}},"required":["correction"],"additionalProperties":false}""",
                        ).jsonObject,
                ),
            )
        }

        val DEVICE_TASK_STOP by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.device.task.stop",
                    "Stop the device task",
                    "",
                    Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
                ),
            )
        }

        val ASK_USER by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.session.ask_user",
                    "Ask the user",
                    "",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"question":{"type":"string","minLength":1}},"required":["question"],"additionalProperties":false}""",
                        ).jsonObject,
                ),
            )
        }
        val BACKGROUND_ANSWER by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.session.background_answer",
                    "Answer a background question",
                    "",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"taskId":{"type":"string","minLength":1},"questionId":{"type":"string","minLength":1},"answer":{"type":"string","minLength":1}},"required":["taskId","questionId","answer"],"additionalProperties":false}""",
                        ).jsonObject,
                ),
            )
        }

        private val DEVICE_CONTROLS = setOf("eva.device.task.revise", "eva.device.task.stop")
        private const val QUESTION_DELIVERY = "question:"

        val DEFER_TO_TEXT by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.session.defer_to_text",
                    "Continue in text",
                    "",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"task":{"type":"string","minLength":1}},"required":["task"],"additionalProperties":false}""",
                        ).jsonObject,
                ),
            )
        }

        val BACKGROUND_STATUS by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.session.background_status",
                    "Background task status",
                    "",
                    Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
                ),
            )
        }

        val BACKGROUND_CANCEL by lazy {
            Wording.bundled.describe(
                ProviderToolDefinition(
                    "eva.session.background_cancel",
                    "Stop a background task",
                    "",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"taskId":{"type":"string","minLength":1}},"required":["taskId"],"additionalProperties":false}""",
                        ).jsonObject,
                ),
            )
        }

        private val BACKGROUND_CONTROLS =
            setOf("eva.session.background_status", "eva.session.background_cancel", "eva.session.background_answer")
        private const val BACKGROUND_STATUS_ITEMS = 512
        private const val BACKGROUND_RECENT_TASKS = 5
        private const val BACKGROUND_START_TIMEOUT_MILLIS = 15_000L

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

fun progressLabel(phase: TaskPhase): String =
    when (phase) {
        TaskPhase.OBSERVING -> "Reading the screen…"
        TaskPhase.THINKING -> "Deciding the next step…"
        TaskPhase.ACTING -> "Acting on the screen…"
        TaskPhase.NEEDS_INPUT -> "Waiting for your answer…"
        TaskPhase.PROGRESS -> "Working on the screen…"
    }

/** Cuts a status field to [limit] characters and ends a cut one with [CLIPPED], which background-status explains. */
internal fun clipped(
    text: String,
    limit: Int,
): String = if (text.length <= limit) text else text.take(limit) + CLIPPED

internal const val CLIPPED = "…[Truncated by EVA]"

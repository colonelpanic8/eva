package com.colonelpanic.eva.call

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Owns delegated phone calls: one long-running `eva.call.start` action per task, the telephony leg
 * a [CallProvider] placed for it, the voice-model brain ([CallAgentBrain]) that conducts the call,
 * the owner's controls, and the journal that lets all of it survive EVA's process.
 *
 * Invariants this class keeps, and why:
 * - Claims are not evidence. The agent's `finish` is recorded as an attributed claim citing
 *   transcript items, never as verification; the task's outcome says so to the owner.
 * - The leg is the truth about the call. Saying goodbye, `finish` or `hang_up` never end a task;
 *   only the provider's observed [LegState.Ended] does, and the outcome is computed then.
 * - Never retry an uncertain mutation. A placement whose result is unknown, a drop around an
 *   agreement, or a lost provider ends Uncertain and asks the owner to check; nothing is redialed.
 * - Secrets never reach the transcript. Keypad credentials are resolved privately just before keys
 *   are sent, recorded as `[credential <ref>]`, and any later echo of a value (a menu reading the
 *   digits back) is scrubbed by [Running.clean] before journaling.
 * - The agent acts only through its restricted catalog ([CallAgentTools]) on its own dispatcher, so
 *   every action is validated, journaled with CALL_AGENT provenance, and receipted like any other.
 * - The owner outranks the agent. Owner controls run through the shared dispatcher too, and taking
 *   the floor cancels the agent's output before anything else happens.
 * - Live captions are UI state ([captions]); only finalized items are journaled.
 *
 * Concurrency: each task's state changes under its [Running.lock]; slow work (placing the call,
 * keypad sequences, owner questions) runs outside it so remote audio and owner controls stay live.
 */
class CallTaskCoordinator(
    private val providers: CallProviderRegistry,
    private val brains: CallAgentBrainFactory,
    private val journal: CallTaskJournal,
    private val invocations: InvocationRepository,
    private val scope: CoroutineScope,
    private val askOwner: suspend (CallOwnerQuestion) -> String,
    private val credentials: CallCredentialResolver,
    /** Null leaves HOLD advisory: without a detector to notice a person returning, the brain keeps listening. */
    private val speechDetector: () -> CallSpeechDetector?,
    private val wording: () -> Wording = { Wording.bundled },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Whether new calls play the agent's voice on the phone until the owner turns it off. */
    private val listenInByDefault: () -> Boolean = { false },
    /**
     * How long a task stays open after its call ends without an outcome claim, so the attached agent
     * can still record one. Bounded because the start tool's result waits on it; when it passes, the
     * task closes exactly as it would have without the window.
     */
    private val claimWindowMillis: Long = WRAP_UP_AFTER_END_MILLIS,
) : ExecutionBackend {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Running>()
    private val recoveryJobs = mutableMapOf<String, Job>()
    private val mutableTasks = MutableStateFlow<Map<String, CallTaskSnapshot>>(emptyMap())
    val tasks = mutableTasks.asStateFlow()
    private val mutableCaptions = MutableStateFlow<Map<String, List<LiveCaption>>>(emptyMap())

    /**
     * Speech in progress per task ID, for the call card to show live whether or not the owner is
     * listening in. Kept apart from [tasks] on purpose: captions are provisional (a cancelled
     * utterance may have stopped partway), so they never enter the journal, callback seeds or
     * outcome evidence; the finalized transcript item with the same ID replaces each one. Held in
     * memory only, so a restarted process shows the journaled transcript without them.
     */
    val captions = mutableCaptions.asStateFlow()

    // Remote audio for providers whose owner does not hear the call natively (hosted media); latest
    // frame wins so a slow consumer never backs up the call.
    private val monitoring =
        MutableSharedFlow<Pair<String, PcmFrame>>(
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val ownerAudio = monitoring.asSharedFlow()

    /**
     * In-memory state of one task while this process conducts it. [snapshot] is what is journaled;
     * everything else is process-local wiring and bookkeeping that recovery rebuilds or gives up on.
     */
    private inner class Running(
        var snapshot: CallTaskSnapshot,
    ) {
        val lock = Mutex()
        val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val done = CompletableDeferred<ExecutionOutcome>()
        val preRoll = CallPreRoll()
        val detector = speechDetector()
        val gatesHold get() = detector != null && leg?.media is CallMedia.LocalPcm
        var leg: CallLeg? = null
        var brain: CallAgentBrain? = null
        var brainEvents: Job? = null
        var brainOpening: Job? = null

        // Increments on every brain swap, so a late tool result from a replaced brain is not delivered to its successor.
        var brainEpoch = 0L

        // Tool results being withheld (owner questions, keypad sequences); a brain expiry waits for them.
        var waiting = 0
        var ownerQuestions = 0
        var replaceRequested = false
        var cancelled = false

        /** The agent asked to hang up before recording an outcome; `finish` hangs up once it records one. */
        var hangUpAfterFinish = false

        /** The leg ended before `finish`; the agent may still record its outcome. */
        var wrappingUpAfterEnd = false

        /** Whether the owner's speech reached the transcript since they last took the floor. */
        var ownerTranscribed = false

        // Credential values sent in this call, held only in memory to scrub echoes of them from text.
        val secrets = mutableSetOf<String>()

        // The agent's own catalog and dispatcher: arguments are scrubbed in prepare(), and execution
        // needs the proposal (for provenance), so the arguments-only entry point refuses.
        val registry =
            CapabilityRegistry(
                CallAgentTools.definitions.associate { definition ->
                    definition.id to
                        object : ExecutionBackend {
                            override fun prepare(proposal: ToolProposal): ToolProposal =
                                proposal.copy(arguments = proposal.arguments.mapValues { clean(it.value) })

                            override suspend fun unavailableReason(): String? = null

                            override suspend fun execute(arguments: Map<String, String>) = error("Call provenance required")

                            override suspend fun execute(proposal: ToolProposal) = agentTool(this@Running, proposal)
                        }
                },
                CallAgentTools.definitions,
            )
        val dispatcher = CapabilityDispatcher(registry, invocations)

        fun clean(text: String): String = secrets.fold(text) { value, secret -> value.replace(secret, "[credential]") }

        /** Journal first: if storage fails, the in-memory state never claims what was not recorded. */
        suspend fun save(next: CallTaskSnapshot = snapshot) {
            journal.save(next)
            snapshot = next
            mutableTasks.update { it + (next.id to next) }
        }

        /** Adds one transcript item, idempotently by ID (replays and replacement brains resend items). */
        suspend fun append(
            id: String,
            speaker: CallSpeaker,
            text: String,
        ): CallTranscriptItem? {
            if (snapshot.transcript.any { it.id == id }) return null
            val item = CallTranscriptItem(id, speaker, clean(text), nowMillis())
            save(snapshot.copy(transcript = snapshot.transcript + item))
            return item
        }
    }

    /** The owner's saved keypad credentials, for telling the main agent which references a call may be given. */
    fun savedCredentials(): List<CallCredential> = credentials.configured()

    /** The call routes this phone has, default first, for telling the main agent which it may choose. */
    fun providerIds(): List<String> = providers.providers.map { it.id }

    fun owns(turnId: String): Boolean = tasks.value.values.any { it.turnId == turnId && !it.state.terminal }

    override suspend fun unavailableReason(): String? = providers.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>) =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A call task needs an owning conversation turn.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val thread = proposal.threadId ?: return execute(proposal.arguments)
        val turn = proposal.turnId ?: return execute(proposal.arguments)
        val args = proposal.arguments
        val provider =
            try {
                providers.choose(args["provider"])
            } catch (error: CallNotPlaced) {
                return rejected("${error.message} No call was placed.")
            }
        val briefCredentials =
            try {
                briefCredentials(args["credentials"]?.let(::strings).orEmpty())
            } catch (error: CallCredentialUnavailable) {
                return rejected("${error.message} No call was placed.")
            }
        val brief =
            CallBrief(
                args.getValue("number"),
                args.getValue("goal"),
                args.getValue("briefing"),
                args.getValue("disclosures"),
                strings(args.getValue("mandate")).toSet(),
                briefCredentials,
                args["onBehalfOf"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        // A callback is a new, owner-approved task seeded with the earlier call's transcript (for
        // reference numbers and context); its mandate and disclosures come fresh from this proposal.
        val previous =
            args["previousTaskId"]?.let { id ->
                tasks.value[id]?.takeIf { it.threadId == thread && it.state.terminal }
                    ?: return rejected(
                        "Callback context must identify an ended call task in this thread. Start a new owner-approved task; do not redial automatically.",
                    )
            }
        val running =
            Running(
                CallTaskSnapshot(
                    proposal.callId,
                    thread,
                    turn,
                    provider.id,
                    provider.traits,
                    brief,
                    transcript = previous?.transcript.orEmpty(),
                    listening = listenInByDefault(),
                ),
            )
        check(sessions.putIfAbsent(proposal.callId, running) == null) { "Call task already admitted" }
        // The task (and its leg ID) is journaled before anything is dialed, so a crash after placement
        // can always find and reattach to the call instead of losing track of a live one.
        try {
            running.lock.withLock { running.save() }
        } catch (_: Exception) {
            sessions.remove(proposal.callId)
            running.work.cancel()
            return rejected("The call task could not be journaled. No call was placed; restore journal storage before trying again.")
        }
        running.work.launch {
            try {
                running.lock.withLock { running.save(running.snapshot.copy(state = CallTaskState.Dialing)) }
                val leg = provider.place(PlaceCallRequest(running.snapshot.legId, brief.number))
                running.leg = leg
                check(leg.id == running.snapshot.legId) { "Provider returned an unstable leg identity" }
                if (running.cancelled) leg.hangUp()
                attachLeg(running, leg)
                running.done.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: CallNotPlaced) {
                notPlaced(running, error.message ?: "The provider did not place the call.")
            } catch (_: Exception) {
                // A failure after place() was called may still have dialed: report it as unknown, never retry.
                failCall(
                    running,
                    "Call setup failed; whether the remote call is still active is unknown. Check the provider before calling again.",
                )
            } finally {
                withContext(NonCancellable) { runCatching { running.brain?.close() } }
                running.work.cancel()
            }
        }
        // The start action stays pending for the whole call; its result is the final outcome.
        return try {
            running.done.await()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { this@CallTaskCoordinator.cancel(proposal.callId) }
            throw error
        }
    }

    /**
     * Follows the leg: its state drives the task (Connected opens the brain, Ended computes the
     * outcome), provider notices become system transcript lines, and local PCM frames feed the brain.
     * The brain opens only once the call is answered, so nothing is spent on rings and no model hears
     * a voicemail greeting before the call connects.
     */
    private fun attachLeg(
        running: Running,
        leg: CallLeg,
    ) {
        running.work.launch {
            guardLeg(running) {
                leg.state.collect { state ->
                    running.lock.withLock {
                        running.save(running.snapshot.copy(legState = state))
                        // Playback starts with the call's audio, so it routes with the call.
                        if (state is LegState.Connected && running.snapshot.listening) leg.setOwnerMonitoring(true)
                        if (state !is LegState.Ended && !running.snapshot.state.terminal) routeOwnerMicrophone(running, leg)
                        when (state) {
                            is LegState.Connected -> {
                                // WrappingUp means the agent already claimed an outcome; reconnect evidence must not reopen the call.
                                if (!running.snapshot.state.terminal && running.snapshot.state != CallTaskState.WrappingUp) {
                                    running.save(running.snapshot.copy(state = CallTaskState.InCall))
                                    if (running.brain == null && running.brainOpening == null &&
                                        !(running.snapshot.phase == CallPhase.HOLD && running.gatesHold)
                                    ) {
                                        startBrain(running)
                                    }
                                }
                            }

                            is LegState.Ended -> {
                                endObserved(running, state)
                            }

                            LegState.Placing, LegState.Ringing -> {
                                Unit
                            }
                        }
                    }
                }
            }
        }
        running.work.launch {
            guardLeg(running) {
                leg.events.collect { event ->
                    if (event is LegEvent.Notice) {
                        running.lock.withLock {
                            running.append(UUID.randomUUID().toString(), CallSpeaker.SYSTEM_IVR, event.detail)
                        }
                    }
                }
            }
        }
        (leg.media as? CallMedia.LocalPcm)?.let { media ->
            running.work.launch { guardLeg(running) { media.remoteFrames.collect { remoteFrame(running, it) } } }
        }
    }

    /** Telephony can reset mute as a call progresses, so the floor's routing is reapplied at each leg state. */
    private suspend fun routeOwnerMicrophone(
        running: Running,
        leg: CallLeg,
    ) {
        try {
            leg.setOwnerMicrophone(running.snapshot.floor == CallFloor.OWNER)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            running.append(
                UUID.randomUUID().toString(),
                CallSpeaker.SYSTEM_IVR,
                "EVA could not route the owner's microphone for the ${running.snapshot.floor} floor " +
                    "(${error.message ?: error::class.simpleName}); the other party may hear the phone's microphone.",
            )
        }
    }

    /** A provider stream that throws means EVA lost sight of the call; the outcome becomes unknown, never assumed. */
    private suspend fun guardLeg(
        running: Running,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failCall(
                running,
                "Provider state or media failed. Remote call and commitment outcomes are unknown. Check before calling again.",
            )
        }
    }

    /**
     * Ends the task as Uncertain and asks the provider to hang up. Runs even if the caller was
     * cancelled, because leaving a live call unattended is worse than an extra hang-up request. If
     * even the journal fails, the in-memory state says so rather than claiming a recorded outcome.
     */
    private suspend fun failCall(
        running: Running,
        reason: String,
        expectedBrainEpoch: Long? = null,
    ) = withContext(NonCancellable) {
        var failed = false
        running.lock.withLock {
            if (expectedBrainEpoch != null &&
                (running.brainEpoch != expectedBrainEpoch || running.snapshot.state.terminal)
            ) {
                return@withLock
            }
            cutOffLiveCaptions(running)
            val next = running.snapshot.copy(state = CallTaskState.Uncertain(reason), brain = BrainState.DETACHED)
            try {
                running.save(next)
            } catch (_: Exception) {
                running.snapshot =
                    next.copy(
                        state =
                            CallTaskState.Uncertain(
                                "Call journal storage failed; call and commitment outcomes are unknown. Restore storage and check the provider before calling again.",
                            ),
                    )
                mutableTasks.update { it + (next.id to running.snapshot) }
            }
            running.done.complete(receipt(running.snapshot))
            failed = true
        }
        if (failed) runCatching { running.leg?.hangUp() }
        Unit
    }

    /**
     * The provider certainly dialed nothing ([CallNotPlaced]): the task ends NotAccomplished with the
     * provider's reason and a FAILED leg. No commitment can be in doubt, so unlike a leg that ended,
     * the outcome stays certain even when journaling it fails.
     */
    private suspend fun notPlaced(
        running: Running,
        reason: String,
    ) = withContext(NonCancellable) {
        running.lock.withLock {
            val next =
                running.snapshot.copy(
                    state = CallTaskState.NotAccomplished("No call was placed: $reason"),
                    legState = LegState.Ended(EndCause.FAILED, reason),
                )
            try {
                running.save(next)
            } catch (_: Exception) {
                running.snapshot = next
                mutableTasks.update { it + (next.id to next) }
            }
            running.done.complete(receipt(running.snapshot))
        }
    }

    /**
     * The provider observed the call end: decide the outcome from what is known. Precedence: the
     * owner cancelling wins; a drop during verification, or around a pending or accepted commitment,
     * is Uncertain (whether the other party agreed is unknown); otherwise the agent's claim stands, or
     * NotAccomplished when there is none.
     *
     * A phone call often ends on the other side's schedule: the far end says goodbye and hangs up
     * while the agent is still speaking its own goodbye, before it had a turn to call finish (seen on
     * the cellular route against the Twilio hotline, and again on the Twilio route). Closing at once
     * would discard an outcome the transcript fully supports. So if the agent had the floor and claimed
     * nothing yet, the task stays WrappingUp for [claimWindowMillis]: only this brain is told the call
     * ended (a callback seeded from the transcript must not be told to finish), its finish is accepted
     * with the usual evidence rules and re-enters here at once, and otherwise this runs again without
     * the allowance when the window passes. Nothing more can happen on the line meanwhile.
     */
    private suspend fun endObserved(
        running: Running,
        ended: LegState.Ended,
    ) {
        val task = running.snapshot
        if (task.state.terminal) return
        cutOffLiveCaptions(running)
        if (!ended.endConfirmed) {
            // Observation failed, so the leg's Ended state closes EVA's media ownership but does not
            // prove the remote line closed. Do not give the brain the confirmed-end follow-up window
            // or promote its earlier attributed finish claim into the task's final outcome.
            val detail = ended.detail?.let { " $it" }.orEmpty()
            running.save(
                task.copy(
                    state =
                        CallTaskState.Uncertain(
                            "EVA lost sight of the call and attempted hang-up, but cannot confirm it ended. " +
                                "The call may still be active; check ${task.providerId} before calling again.$detail",
                        ),
                    brain = BrainState.DETACHED,
                ),
            )
            running.done.complete(receipt(running.snapshot))
            return
        }
        val brain = running.brain
        if (!running.cancelled && task.proposedOutcome == null && brain != null && !running.wrappingUpAfterEnd &&
            claimWindowMillis > 0 && task.floor in setOf(CallFloor.AGENT, CallFloor.AGENT_STALLING)
        ) {
            running.wrappingUpAfterEnd = true
            running.save(task.copy(state = CallTaskState.WrappingUp))
            brain.legEnded(
                CallTranscriptItem(
                    UUID.randomUUID().toString(),
                    CallSpeaker.SYSTEM_IVR,
                    wording().message(Wording.CALL_ENDED_BEFORE_FINISH).replace("{cause}", ended.cause.name),
                    nowMillis(),
                ),
            )
            running.work.launch {
                delay(claimWindowMillis)
                running.lock.withLock { endObserved(running, ended) }
            }
            return
        }
        val commitment = task.pendingCommitment ?: task.acceptedCommitments.lastOrNull()
        val uncertain =
            commitment?.let {
                CallTaskState.Uncertain(
                    "Whether the remote party accepted/completed '$it' is unknown after ${ended.cause}. Check before any new call.",
                )
            }
        val abrupt = ended.cause in setOf(EndCause.DROPPED, EndCause.FAILED)
        val incompleteConfirmation = task.phase == CallPhase.VERIFYING && task.proposedOutcome == null
        val outcome =
            when {
                running.cancelled -> {
                    CallTaskState.Interrupted("Owner cancelled the call.", uncertain)
                }

                incompleteConfirmation -> {
                    CallTaskState.Uncertain(
                        "Whether '${task.brief.goal}' completed and what confirmation/reference was issued are unknown; the leg ended during verification.",
                    )
                }

                uncertain != null && (abrupt || task.pendingCommitment != null || task.proposedOutcome == null) -> {
                    uncertain
                }

                task.proposedOutcome != null -> {
                    task.proposedOutcome
                }

                else -> {
                    CallTaskState.NotAccomplished("Call ended (${ended.cause}) without an evidenced outcome claim.")
                }
            }
        running.save(task.copy(state = outcome, brain = BrainState.DETACHED))
        running.done.complete(receipt(running.snapshot))
    }

    /**
     * Opens a brain seeded with everything it needs to continue the call: stock instructions and
     * tool wording from the instruction catalog (soft content, not Kotlin), the brief, and the whole
     * attributed transcript so far, which is how a replacement brain picks up mid-call without
     * repeating keys or commitments. Tool calls run concurrently (a keypad sequence or an owner
     * question can take minutes); every other event is applied under the task lock and only if this
     * brain is still the current one, so a replaced brain's late events change nothing.
     *
     * A deliberate invariant the stock instructions carry: the agent speaks as someone calling on
     * the owner's behalf (by name, from `callBrief.onBehalfOf`) without announcing up front that it
     * is an AI, but it never denies being one when the other party sincerely asks; it then answers
     * briefly and honestly and carries on. Rewording in the catalog must keep both halves
     * when revising the catalog.
     */
    private fun startBrain(running: Running) {
        val leg = checkNotNull(running.leg)
        val text = wording()
        val catalog =
            CallAgentTools.definitions.map {
                text.describe(ProviderToolDefinition(it.id, it.title, it.description, it.inputSchema))
            }
        val request =
            CallBrainRequest(
                text.message(Wording.CALL_AGENT_INSTRUCTIONS),
                running.snapshot.brief,
                running.snapshot.transcript.toList(),
                catalog,
                running.snapshot.traits,
                leg.media,
            )
        val epoch = ++running.brainEpoch
        // Connection and seed acknowledgements can wait on the network. The owner must retain the
        // task lock for floor changes and hang-up while that happens. The epoch prevents a late open
        // from attaching after a replacement, detach, or ended call.
        running.brainOpening =
            running.work.launch {
                var opened: CallAgentBrain? = null
                try {
                    opened = brains.open(request)
                    running.lock.withLock {
                        if (running.brainEpoch == epoch && !running.snapshot.state.terminal &&
                            running.snapshot.legState !is LegState.Ended && !running.cancelled
                        ) {
                            val brain = opened
                            checkNotNull(brain)
                            brain.setFloor(running.snapshot.floor)
                            running.save(running.snapshot.copy(brain = BrainState.ATTACHED))
                            running.brain = brain
                            running.brainOpening = null
                            opened = null
                            collectBrain(running, leg, brain, epoch)
                            // Controls and owner speech can now arrive during setup. The request was
                            // seeded from an earlier snapshot, so deliver only the later journaled items.
                            val seeded = request.transcript.mapTo(mutableSetOf()) { it.id }
                            running.snapshot.transcript
                                .filterNot { it.id in seeded }
                                .forEach { brain.appendContext(it) }
                            running.preRoll.drain().forEach { brain.pushRemotePcm(it) }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failCall(
                        running,
                        "Call brain setup failed. Remote call and commitment outcomes are unknown. Check before calling again.",
                        expectedBrainEpoch = epoch,
                    )
                } finally {
                    // A factory can return after its caller was cancelled. It owns cleanup only until
                    // it returns; once we have a brain, close it if this epoch did not take ownership.
                    opened?.let { withContext(NonCancellable) { it.close() } }
                    withContext(NonCancellable) {
                        running.lock.withLock {
                            if (running.brainEpoch == epoch) running.brainOpening = null
                        }
                    }
                }
            }
    }

    private fun collectBrain(
        running: Running,
        leg: CallLeg,
        brain: CallAgentBrain,
        epoch: Long,
    ) {
        running.brainEvents =
            running.work.launch {
                try {
                    brain.events.collect { event ->
                        when (event) {
                            is CallBrainEvent.ToolCall -> {
                                running.work.launch { dispatchAgentTool(running, brain, epoch, event) }
                            }

                            else -> {
                                running.lock.withLock {
                                    if (running.brain !== brain || running.snapshot.state.terminal) return@withLock
                                    when (event) {
                                        is CallBrainEvent.Audio -> {
                                            // AGENT_STALLING still speaks ("one moment") while an owner question is pending.
                                            if (running.snapshot.floor in setOf(CallFloor.AGENT, CallFloor.AGENT_STALLING) &&
                                                running.snapshot.legState !is LegState.Ended
                                            ) {
                                                (leg.media as? CallMedia.LocalPcm)?.sendAgentFrame(event.frame)
                                            }
                                        }

                                        is CallBrainEvent.Caption -> {
                                            caption(running, event)
                                        }

                                        is CallBrainEvent.Transcript -> {
                                            // The finalized item replaces the live caption it grew from.
                                            updateCaptions(running.snapshot.id) { list -> list.filterNot { it.id == event.id } }
                                            require(event.speaker in setOf(CallSpeaker.REMOTE, CallSpeaker.AGENT, CallSpeaker.SYSTEM_IVR))
                                            // Its audio was dropped: nobody heard speech generated after the call ended.
                                            if (event.speaker != CallSpeaker.AGENT || running.snapshot.legState !is LegState.Ended) {
                                                running.append(event.id, event.speaker, event.text)
                                            }
                                        }

                                        CallBrainEvent.OutputCancelled -> {
                                            (leg.media as? CallMedia.LocalPcm)?.cancelAgentAudio()
                                        }

                                        CallBrainEvent.Expired -> {
                                            // Never mid-tool: a withheld result must reach the brain that asked, or its successor once delivered.
                                            if (running.waiting > 0) running.replaceRequested = true else replaceBrain(running)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    running.lock.withLock {
                        cutOffLiveCaptions(running)
                        running.save(running.snapshot.copy(brain = BrainState.DETACHED))
                        running.brain = null
                        running.append(
                            UUID.randomUUID().toString(),
                            CallSpeaker.SYSTEM_IVR,
                            "Call brain failed. Telephony leg remains independently controlled; replace the brain or take over.",
                        )
                    }
                }
            }
    }

    /** Captions live only in [mutableCaptions]; a task with none has no entry, so the map stays small. */
    private fun updateCaptions(
        taskId: String,
        change: (List<LiveCaption>) -> List<LiveCaption>,
    ) = mutableCaptions.update { all ->
        val next = change(all[taskId].orEmpty())
        if (next.isEmpty()) all - taskId else all + (taskId to next)
    }

    /**
     * Applies one caption update. The start time is taken from the first update, so the card can
     * place the caption among finalized lines in the order things were said.
     */
    private fun caption(
        running: Running,
        event: CallBrainEvent.Caption,
    ) {
        val taskId = running.snapshot.id
        when (event.state) {
            CaptionState.LIVE -> {
                // Agent speech after the leg ended is dropped before it reaches the line, so it is not shown as said.
                if (event.speaker == CallSpeaker.AGENT && running.snapshot.legState is LegState.Ended) return
                updateCaptions(taskId) { list ->
                    val existing = list.firstOrNull { it.id == event.id }
                    if (existing != null) {
                        list.map { if (it.id == event.id) it.copy(text = event.text, cutOff = false) else it }
                    } else {
                        list + LiveCaption(event.id, event.speaker, event.text, cutOff = false, startedAtMillis = nowMillis())
                    }
                }
            }

            CaptionState.CUT_OFF -> {
                updateCaptions(taskId) { list ->
                    val existing = list.firstOrNull { it.id == event.id }
                    val text = event.text.ifBlank { existing?.text.orEmpty() }
                    when {
                        existing != null -> list.map { if (it.id == event.id) it.copy(text = text, cutOff = true) else it }
                        text.isNotBlank() -> list + LiveCaption(event.id, event.speaker, text, cutOff = true, startedAtMillis = nowMillis())
                        else -> list
                    }
                }
            }

            CaptionState.DROPPED -> {
                updateCaptions(taskId) { list -> list.filterNot { it.id == event.id } }
            }
        }
    }

    /**
     * Whatever was still being said when the call, or the brain producing it, went away will never
     * be finalized: mark it cut off rather than leaving it looking live or letting it vanish.
     */
    private fun cutOffLiveCaptions(running: Running) =
        updateCaptions(running.snapshot.id) { list -> list.map { if (it.cutOff) it else it.copy(cutOff = true) } }

    private suspend fun detachBrain(running: Running) {
        cutOffLiveCaptions(running)
        running.brainOpening?.cancel()
        running.brainOpening = null
        val old = running.brain
        running.brain = null
        running.brainEvents?.cancel()
        running.brainEpoch++
        old?.cancelOutput()
        (running.leg?.media as? CallMedia.LocalPcm)?.cancelAgentAudio()
        old?.close()
        running.save(running.snapshot.copy(brain = BrainState.DETACHED))
    }

    /**
     * Swaps in a fresh brain on the same call after the old session expired or failed; the call is
     * never redialed. The old brain's output and queued audio are cancelled first so two brains never
     * speak at once.
     */
    private suspend fun replaceBrain(running: Running) {
        cutOffLiveCaptions(running)
        running.save(running.snapshot.copy(brain = BrainState.REPLACING))
        // Cancel collection without cancelling this operation when expiry arrived on that collector.
        val oldEvents = running.brainEvents
        running.brainEvents = null
        val old = running.brain
        running.brain = null
        running.brainOpening?.cancel()
        running.brainOpening = null
        old?.cancelOutput()
        (running.leg?.media as? CallMedia.LocalPcm)?.cancelAgentAudio()
        old?.close()
        startBrain(running)
        oldEvents?.cancel()
    }

    /**
     * One frame of the other party's audio. During HOLD with a local speech detector, frames are kept
     * in a short pre-roll and the brain stays detached (no model time on hold music) until speech
     * returns, when the pre-roll is replayed so the first words are not lost. Without a detector
     * (the current Android wiring) every frame goes to the brain, which decides for itself.
     */
    private suspend fun remoteFrame(
        running: Running,
        frame: PcmFrame,
    ) = running.lock.withLock {
        if (running.snapshot.state.terminal) return@withLock
        if (running.snapshot.listening) monitoring.tryEmit(running.snapshot.id to frame)
        val detector = running.detector
        if (running.snapshot.phase == CallPhase.HOLD && detector != null && running.gatesHold) {
            running.preRoll.add(frame)
            if (!detector.speechDetected(frame)) return@withLock
            running.save(running.snapshot.copy(phase = CallPhase.UNKNOWN))
            if (running.brain == null && running.brainOpening == null) startBrain(running)
            if (running.brain != null) running.preRoll.drain().forEach { running.brain?.pushRemotePcm(it) }
        } else {
            if (running.brainOpening != null) running.preRoll.add(frame) else running.brain?.pushRemotePcm(frame)
        }
    }

    /**
     * Runs one agent tool call through the task's dispatcher and journals its receipt before
     * delivering it, so a replacement brain can be reseeded with results whose original socket is
     * gone. The call ID includes the brain epoch: a replayed or late call from another brain can
     * never collide with, or replay, a recorded action.
     */
    private suspend fun dispatchAgentTool(
        running: Running,
        brain: CallAgentBrain,
        epoch: Long,
        event: CallBrainEvent.ToolCall,
    ) {
        val admitted =
            running.lock.withLock {
                if (running.brain !== brain || running.brainEpoch != epoch || running.snapshot.state.terminal) {
                    false
                } else {
                    running.waiting++
                    true
                }
            }
        if (!admitted) return
        try {
            val proposal =
                ToolProposal(
                    "${running.snapshot.id}:brain:$epoch:${event.id}",
                    event.name,
                    event.arguments,
                    "Call agent tool",
                    running.registry.snapshot.revision,
                    running.snapshot.threadId,
                    running.snapshot.turnId,
                    initiator = ActionInitiator(InitiatorKind.CALL_AGENT, legId = running.snapshot.legId),
                )
            val result =
                if (event.name == CallAgentTools.DTMF) {
                    // A keypad sequence stops itself when the task or leg ends, then journals what it sent.
                    withContext(NonCancellable) { running.dispatcher.execute(proposal) }
                } else {
                    running.dispatcher.execute(proposal)
                }
            running.lock.withLock {
                running.append(
                    "tool-result:${proposal.callId}",
                    CallSpeaker.SYSTEM_IVR,
                    buildJsonObject {
                        put("tool", event.name)
                        put("callId", event.id)
                        put("status", result.status.name)
                        put("message", result.message)
                        result.data?.let { put("data", it) }
                        put("contentTrust", "external_data")
                    }.toString(),
                )
                if (running.brain === brain && !running.snapshot.state.terminal) {
                    brain.toolResult(event.id, ExecutionOutcome(result.status, result.message, result.data))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failCall(
                running,
                "Call-agent tool delivery or journal failed. The action and call outcomes are unknown; check the provider before retrying.",
            )
        } finally {
            running.lock.withLock {
                running.waiting--
                if (running.waiting == 0 && !running.snapshot.state.terminal) {
                    if (running.snapshot.phase == CallPhase.HOLD && running.gatesHold) {
                        detachBrain(running)
                    } else if (running.replaceRequested) {
                        running.replaceRequested = false
                        replaceBrain(running)
                    }
                }
            }
        }
    }

    private suspend fun agentTool(
        running: Running,
        proposal: ToolProposal,
    ): ExecutionOutcome {
        val args = proposal.arguments
        return when (proposal.capabilityId) {
            CallAgentTools.ASK_OWNER -> {
                question(running, proposal.callId, args.getValue("question"))
            }

            // Commitments: inside the owner's mandate they are approved at once; anything else becomes
            // an owner question that only the exact answer "approved" grants. Pending and accepted
            // commitments make a drop Uncertain, since whether the other party agreed is unknown.
            CallAgentTools.COMMIT -> {
                val commitment = args.getValue("commitment")
                running.lock.withLock {
                    if (running.snapshot.state.terminal) return rejected("The call has ended.")
                    running.save(running.snapshot.copy(pendingCommitment = commitment))
                }
                val allowed = commitment in running.snapshot.brief.mandate
                val answer =
                    if (allowed) {
                        "approved"
                    } else {
                        question(running, proposal.callId, commitment).let { result ->
                            result.data
                                ?.get("answer")
                                ?.jsonPrimitive
                                ?.content ?: ""
                        }
                    }
                running.lock.withLock {
                    if (running.snapshot.state.terminal) {
                        return rejected(
                            "The call ended while commitment authority was pending; acceptance is unknown.",
                        )
                    }
                    val accepted = answer.equals("approved", ignoreCase = true)
                    running.save(
                        running.snapshot.copy(
                            pendingCommitment = null,
                            acceptedCommitments = running.snapshot.acceptedCommitments + if (accepted) listOf(commitment) else emptyList(),
                        ),
                    )
                    if (accepted) {
                        completed("Authorized to state this commitment. Remote acceptance is not verified.")
                    } else {
                        rejected("Owner did not explicitly approve this commitment. Do not agree.")
                    }
                }
            }

            CallAgentTools.DTMF -> {
                dtmf(running, strings(args.getValue("sequence")))
            }

            else -> {
                running.lock.withLock {
                    if (running.snapshot.state.terminal) return rejected("The call has ended.")
                    when (proposal.capabilityId) {
                        CallAgentTools.PHASE -> {
                            running.save(running.snapshot.copy(phase = CallPhase.valueOf(args.getValue("phase"))))
                            completed("Advisory conversation phase recorded.")
                        }

                        CallAgentTools.KEEP_LISTENING -> {
                            completed("Silent; still listening.")
                        }

                        CallAgentTools.HANG_UP -> {
                            // The task ends with the leg, so hanging up before `finish` would lose the outcome
                            // (it did on the device). The request is remembered and honoured by the next finish.
                            if (running.snapshot.proposedOutcome == null) {
                                running.hangUpAfterFinish = true
                                return rejected(
                                    "Not hung up yet: no outcome is recorded, and the task ends when the call does. " +
                                        "Call finish now; EVA hangs up as soon as it records the outcome.",
                                )
                            }
                            hangUpAfterSpeech(running)
                            completed(
                                "Hang-up requested; it goes through once anything you already said has finished playing. " +
                                    "The provider's leg state determines when the call has ended.",
                            )
                        }

                        CallAgentTools.FINISH -> {
                            // The claim must cite what was actually said: an entry matching nothing rejects the
                            // whole finish (nothing recorded) and the receipt names the closest items, so the agent
                            // retries with their IDs, rather than an outcome resting on a fabricated or paraphrased
                            // quote. The claim stays proposed (WrappingUp) until the leg ends; if it already ended,
                            // the task concludes now.
                            val cited = resolveEvidence(running.snapshot.transcript, strings(args.getValue("evidence")))
                            val unmatched = cited.filter { it.items.isEmpty() }.map { it.cited }
                            if (unmatched.isNotEmpty()) {
                                return rejected(unmatchedEvidence(running.snapshot.transcript, unmatched))
                            }
                            val evidence = cited.flatMap { it.items }.distinct()
                            if (args.getValue("outcome") == "accomplished" &&
                                evidence.isEmpty()
                            ) {
                                return rejected("An accomplishment claim needs transcript evidence.")
                            }
                            val outcome =
                                when (args.getValue("outcome")) {
                                    "accomplished" -> CallTaskState.Accomplished(evidence, args.getValue("summary"))
                                    "not_accomplished" -> CallTaskState.NotAccomplished(args.getValue("summary"))
                                    else -> CallTaskState.Uncertain(args.getValue("summary"))
                                }
                            // finish more than once (seen on a Twilio call: again after the other party's "bye"):
                            // the latest claim replaces the earlier one while the task is open, since the call can
                            // still change what happened (a corrected reference number). Repeating the same claim
                            // changes nothing and says so, so the agent does not keep re-recording it. Once the
                            // task has ended no claim is accepted (the terminal check above).
                            val previous = running.snapshot.proposedOutcome
                            if (previous == outcome) {
                                return completed(
                                    "This outcome claim is already recorded; nothing changed. Call finish again only if the outcome changes.",
                                )
                            }
                            running.save(running.snapshot.copy(state = CallTaskState.WrappingUp, proposedOutcome = outcome))
                            (running.snapshot.legState as? LegState.Ended)?.let { endObserved(running, it) }
                            val hangUp = running.hangUpAfterFinish && running.snapshot.legState !is LegState.Ended
                            if (hangUp) {
                                running.hangUpAfterFinish = false
                                hangUpAfterSpeech(running)
                            }
                            completed(
                                (if (previous != null) "Replaced your earlier outcome claim with this one. " else "") +
                                    if (hangUp) {
                                        "Outcome claim recorded with transcript references. Hang-up requested as you asked earlier; " +
                                            "the provider's leg state determines when the call has ended."
                                    } else {
                                        "Outcome claim recorded with transcript references. The call remains active until the provider observes its end."
                                    },
                                buildJsonObject {
                                    put(
                                        "evidence",
                                        JsonArray(
                                            cited.map { citation ->
                                                buildJsonObject {
                                                    put(if (citation.quote) "quote" else "id", citation.cited)
                                                    put("items", JsonArray(citation.items.map(::JsonPrimitive)))
                                                }
                                            },
                                        ),
                                    )
                                },
                            )
                        }

                        else -> {
                            rejected("This tool is outside the call agent's restricted catalog.")
                        }
                    }
                }
            }
        }
    }

    /**
     * The agent's own hang-up. A model response carries its speech and its tool calls together, and the
     * tools are only released when the response completes, by which time all of its audio is queued but
     * most of it has not played: the uplink is paced in real time. Hanging up at once would cut off the
     * goodbye spoken in the same response, so the leg hangs up once queued agent audio has played (or
     * was cleared, as a barge-in or takeover does). Runs outside the task lock so remote audio, captions
     * and owner controls stay live meanwhile; an owner cancel hangs up directly and is never delayed.
     */
    private fun hangUpAfterSpeech(running: Running) {
        val leg = checkNotNull(running.leg)
        running.work.launch {
            guardLeg(running) {
                (leg.media as? CallMedia.LocalPcm)?.awaitAgentAudioPlayed()
                leg.hangUp()
            }
        }
    }

    /**
     * Asks the owner and withholds the tool result until they answer; the agent holds the line with
     * AGENT_STALLING ("one moment") meanwhile. Several open questions keep that floor until the last
     * resolves, and an owner takeover in between stays in force.
     */
    private suspend fun question(
        running: Running,
        id: String,
        text: String,
    ): ExecutionOutcome {
        running.lock.withLock {
            if (running.snapshot.state.terminal) return rejected("The call has ended.")
            running.ownerQuestions++
            if (running.snapshot.floor == CallFloor.AGENT) setFloor(running, CallFloor.AGENT_STALLING)
        }
        try {
            val answer =
                askOwner(
                    CallOwnerQuestion(
                        running.snapshot.threadId,
                        running.snapshot.turnId,
                        running.snapshot.id,
                        id,
                        text,
                        running.snapshot.legState is LegState.Connected,
                    ),
                )
            return running.lock.withLock {
                if (running.snapshot.state.terminal) return rejected("The call ended before this answer could be delivered.")
                val item = running.append("answer:$id", CallSpeaker.OWNER_TO_AGENT, answer)
                item?.let { running.brain?.appendContext(it) }
                completed("Owner answered.", buildJsonObject { put("answer", running.clean(answer)) })
            }
        } finally {
            withContext(NonCancellable) {
                running.lock.withLock {
                    running.ownerQuestions--
                    if (running.ownerQuestions == 0 && !running.snapshot.state.terminal &&
                        running.snapshot.floor == CallFloor.AGENT_STALLING
                    ) {
                        setFloor(running, CallFloor.AGENT)
                    }
                }
            }
        }
    }

    /**
     * Sends a whole keypad sequence without returning to the model between steps; the brain's pending
     * tool keeps it silent meanwhile. Runs outside the task lock so remote audio, owner takeover,
     * cancellation and leg events proceed, and stops between keys or mid-wait when any of them ends
     * the agent's turn. The receipt names exactly what was sent.
     */
    private suspend fun dtmf(
        running: Running,
        sequence: List<String>,
    ): ExecutionOutcome {
        if (!running.snapshot.traits.supportsDtmf) return rejected("This provider does not support DTMF.")
        val steps =
            try {
                DtmfStep.parse(sequence)
            } catch (error: IllegalArgumentException) {
                return rejected("${error.message} No keys were sent.")
            }
        // Credential values: resolved only here, only for references this call's brief was given (so
        // remote speech cannot steer the agent into another saved secret), all before the first key
        // goes out, and wiped in finally. They exist as text only in [Running.secrets], for scrubbing.
        val values = mutableMapOf<String, CharArray>()
        try {
            val allowed =
                running.snapshot.brief.credentials
                    .map { it.reference }
            for (reference in steps.filterIsInstance<DtmfStep.Credential>().map { it.reference }.distinct()) {
                if (reference !in allowed) {
                    val given = if (allowed.isEmpty()) "this call was given none" else "this call may enter only ${allowed.joinToString()}"
                    return rejected("Credential $reference is not in this call's brief; $given. No keys were sent.")
                }
                values[reference] =
                    try {
                        keypadValue(reference)
                    } catch (error: CallCredentialUnavailable) {
                        return rejected("${error.message} No keys were sent.")
                    }
            }
            val keys =
                steps.map { step ->
                    when (step) {
                        is DtmfStep.Keys -> step.keys
                        is DtmfStep.Credential -> String(values.getValue(step.reference)).uppercase()
                        is DtmfStep.Wait -> ""
                    }
                }
            if (!running.snapshot.traits.dtmfLetterKeys && keys.any { step -> step.any { it in DtmfStep.LETTER_KEYS } }) {
                return rejected("This call's keypad route sends only 0-9, * and #. No keys were sent.")
            }
            running.lock.withLock {
                if (running.snapshot.state.terminal) return rejected("The call has ended. No keys were sent.")
                running.secrets += values.values.map(::String)
            }
            val leg = checkNotNull(running.leg)
            val done = mutableListOf<String>()
            val methods = linkedSetOf<String>()
            var pressed = 0

            fun stopped(
                where: String,
                reason: String,
            ): ExecutionOutcome {
                val sent = if (done.isEmpty()) "Nothing was sent" else "Sent ${done.joinToString(", ")}"
                val message = "$sent. Stopped $where because $reason; nothing after that was sent. ${methods.joinToString(" ")}".trim()
                return ExecutionOutcome(if (pressed == 0) InvocationStatus.NOT_EXECUTED else InvocationStatus.FAILED, message)
            }

            for ((index, step) in steps.withIndex()) {
                dtmfStopReason(running)?.let { return stopped("before ${step.label}", it) }
                running.lock.withLock { running.append("dtmf:${UUID.randomUUID()}", CallSpeaker.DTMF, step.label) }
                if (step is DtmfStep.Wait) {
                    withTimeoutOrNull(step.millis) {
                        merge(tasks, leg.state).map { dtmfStopReason(running) }.first { it != null }
                    }?.let { return stopped("during ${step.label}", it) }
                } else {
                    for ((count, key) in keys[index].withIndex()) {
                        if (count > 0) dtmfStopReason(running)?.let { return stopped("after $count key(s) of ${step.label}", it) }
                        try {
                            methods += leg.sendDtmf(key.toString())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // A key the provider failed on may or may not have reached the line: UNKNOWN, and the
                            // agent is told to check the prompt rather than resend (never retry an uncertain mutation).
                            val sent = if (done.isEmpty()) "No earlier step was sent" else "Sent ${done.joinToString(", ")}"
                            val why = dtmfStopReason(running) ?: "the provider failed while pressing it"
                            return ExecutionOutcome(
                                InvocationStatus.UNKNOWN,
                                "$sent. Whether key ${count + 1} of ${step.label} reached the call is unknown because $why; nothing " +
                                    "after it was sent. Check the remote prompt before sending again.",
                            )
                        }
                        pressed++
                    }
                }
                done += step.label
            }
            return completed("Sent the whole sequence: ${done.joinToString(", ")}. ${methods.joinToString(" ")}".trim())
        } finally {
            values.values.forEach { it.fill('\u0000') }
        }
    }

    /**
     * The credentials a new call may enter, each resolved and checked once so a missing or unusable
     * value fails before dialing rather than at the keypad.
     */
    private suspend fun briefCredentials(references: List<String>): List<CallCredential> {
        if (references.isEmpty()) return emptyList()
        val labels = credentials.configured().associate { it.reference to it.label }
        return references.distinct().map { reference ->
            keypadValue(reference).fill('\u0000')
            CallCredential(reference, labels[reference] ?: reference)
        }
    }

    /** Throws [CallCredentialUnavailable] with a message that never holds the value. */
    private suspend fun keypadValue(reference: String): CharArray {
        val value =
            try {
                credentials.resolve(reference)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CallCredentialUnavailable) {
                throw error
            } catch (_: Exception) {
                throw CallCredentialUnavailable("Credential $reference could not be read.")
            }
        if (value.isEmpty() || value.any { it.uppercaseChar() !in DtmfStep.KEYS }) {
            value.fill('\u0000')
            throw CallCredentialUnavailable("Credential $reference is not keypad keys; the owner must re-enter it as digits, * or #.")
        }
        return value
    }

    /** Why a keypad sequence must stop before its next step, or null to continue. */
    private fun dtmfStopReason(running: Running): String? {
        val task = running.snapshot
        return when {
            running.leg?.state?.value is LegState.Ended -> "the call ended"
            task.state.terminal -> "the call task ended"
            running.cancelled -> "the owner cancelled the call task"
            task.floor == CallFloor.OWNER -> "the owner took over the call"
            task.floor == CallFloor.MUTED -> "EVA was muted"
            else -> null
        }
    }

    /**
     * Moves the speaking floor. Leaving AGENT cancels the model's output and the provider's queued
     * audio before anything is journaled, so the agent stops talking at once. The owner's microphone
     * follows: open on OWNER, muted otherwise (the agent speaks through the injection, and an open
     * mic would mix the room into it). On handback the cellular provider cannot transcribe the owner,
     * so the agent is told it missed what they said instead of carrying on as if nothing happened.
     */
    private suspend fun setFloor(
        running: Running,
        floor: CallFloor,
    ) {
        val previous = running.snapshot.floor
        if (previous == CallFloor.OWNER && floor == CallFloor.AGENT && !running.ownerTranscribed) {
            running
                .append(UUID.randomUUID().toString(), CallSpeaker.SYSTEM_IVR, wording().message(Wording.CALL_OWNER_UNTRANSCRIBED))
                ?.let { running.brain?.appendContext(it) }
        }
        if (floor == CallFloor.OWNER && previous != CallFloor.OWNER) running.ownerTranscribed = false
        val next = running.snapshot.copy(floor = floor)
        running.snapshot = next
        if (floor != CallFloor.AGENT) {
            running.brain?.cancelOutput()
            (running.leg?.media as? CallMedia.LocalPcm)?.cancelAgentAudio()
        }
        running.brain?.setFloor(floor)
        running.leg?.setOwnerMicrophone(floor == CallFloor.OWNER)
        running.save(next)
    }

    /** Invoke at speech START, before waiting for a final owner caption. There is no silence handback. */
    suspend fun ownerSpeechStarted(taskId: String) {
        val running = sessions.getValue(taskId)
        running.lock.withLock { if (!running.snapshot.state.terminal) setFloor(running, CallFloor.OWNER) }
    }

    suspend fun ownerTranscript(
        taskId: String,
        itemId: String,
        text: String,
    ) {
        val running = sessions.getValue(taskId)
        running.lock.withLock {
            if (running.snapshot.state.terminal) return
            running.ownerTranscribed = true
            running.append(itemId, CallSpeaker.OWNER_TO_REMOTE, text)?.let { running.brain?.appendContext(it) }
        }
    }

    /**
     * The owner's call controls (status, floor, listen, whisper, cancel), proposed from the card or by
     * the main agent through the shared dispatcher. Scoped to the proposing thread so one
     * conversation cannot steer another's call.
     */
    suspend fun control(proposal: ToolProposal): ExecutionOutcome {
        if (proposal.capabilityId == CallCapabilities.STATUS) {
            val found =
                tasks.value.values.filter {
                    it.threadId == proposal.threadId && (proposal.arguments["taskId"] == null || it.id == proposal.arguments["taskId"])
                }
            return completed(
                "Call task status; outcomes are attributed claims.",
                buildJsonObject {
                    put("contentTrust", "external_data")
                    put("tasks", JsonArray(found.map { Json.encodeToJsonElement(CallTaskSnapshot.serializer(), it) }))
                },
            )
        }
        val running =
            sessions[proposal.arguments["taskId"]]?.takeIf { it.snapshot.threadId == proposal.threadId }
                ?: return rejected("No call task with that ID belongs to this thread.")
        if (proposal.capabilityId == CallCapabilities.CANCEL) return cancel(running.snapshot.id)
        return running.lock.withLock {
            if (proposal.capabilityId == CallCapabilities.STATUS) {
                return completed(
                    "Call task status; outcomes are attributed claims.",
                    buildJsonObject {
                        put("contentTrust", "external_data")
                        put("callTask", Json.encodeToJsonElement(CallTaskSnapshot.serializer(), running.snapshot))
                    },
                )
            }
            if (running.snapshot.state.terminal) return rejected("The call task has ended.")
            when (proposal.capabilityId) {
                CallCapabilities.INSTRUCT -> {
                    running
                        .append(
                            proposal.callId,
                            CallSpeaker.OWNER_TO_AGENT,
                            proposal.arguments.getValue("text"),
                        )?.let { running.brain?.appendContext(it) }
                }

                CallCapabilities.FLOOR -> {
                    setFloor(running, CallFloor.valueOf(proposal.arguments.getValue("floor")))
                }

                CallCapabilities.LISTEN -> {
                    val enabled = proposal.arguments.getValue("enabled").toBooleanStrict()
                    running.leg?.setOwnerMonitoring(enabled)
                    running.save(running.snapshot.copy(listening = enabled))
                }

                else -> {
                    return rejected("Unknown call control.")
                }
            }
            completed("Call control applied.")
        }
    }

    /**
     * The owner (or the main agent) ends the call. The task records the request at once, but only the
     * provider's observed end completes it; the receipt says so rather than claiming the call is over.
     */
    suspend fun cancel(taskId: String): ExecutionOutcome {
        val running = sessions[taskId] ?: return rejected("Unknown call task.")
        return running.lock.withLock {
            if (running.snapshot.state.terminal && running.snapshot.legState is LegState.Ended) return rejected("The call task has ended.")
            running.cancelled = true
            setFloor(running, CallFloor.MUTED)
            running.leg?.hangUp()
            completed("Cancellation and hang-up requested. Awaiting provider evidence of the leg ending.")
        }
    }

    fun stop(turnId: String) {
        sessions.values.filter { it.snapshot.turnId == turnId && !it.snapshot.state.terminal }.forEach { running ->
            scope.launch { this@CallTaskCoordinator.cancel(running.snapshot.id) }
        }
    }

    /**
     * Runs at process start. Recovery never replays placement or a withheld question. A local-PCM call cannot continue without the process that carried its
     * audio and brain, so recovery does not resume tasks: it reattaches to any call the provider says
     * is still live only to hang it up, and records an honest end: Interrupted, Uncertain when a
     * commitment was pending or accepted, or Uncertain asking for provider intervention when the leg
     * cannot be reached. It never redials. Not device-verified on the cellular provider.
     */
    suspend fun recover() {
        journal.latest().forEach { task ->
            mutableTasks.update { it + (task.id to task) }
            // Even a terminal uncertain setup can still have a live remote leg.
            if (task.state.terminal && task.legState is LegState.Ended) return@forEach
            val commitment = task.pendingCommitment ?: task.acceptedCommitments.lastOrNull()
            val uncertain = commitment?.let { CallTaskState.Uncertain("Acceptance/completion of '$it' is unknown after process death.") }
            var recovered =
                task.copy(
                    state = CallTaskState.Interrupted("EVA process ended; pending owner questions are interrupted.", uncertain),
                    brain = BrainState.DETACHED,
                    floor = CallFloor.MUTED,
                )
            var recoveredLeg: CallLeg? = null
            if (task.traits.mediaKind == CallMediaKind.MODEL_HOSTED || task.traits.survivesProcessDeath) {
                val provider = providers.providers.find { it.id == task.providerId }
                try {
                    checkNotNull(provider) { "Provider missing" }
                    val leg = provider.reattach(task.legId)
                    if (leg != null) {
                        recoveredLeg = leg
                        leg.hangUp()
                        recovered = recovered.copy(legState = leg.state.value)
                        if (leg.state.value !is LegState.Ended) {
                            recovered =
                                recovered.copy(
                                    state =
                                        CallTaskState.Uncertain(
                                            "Recovered hosted call; hang-up requested but its end is not yet observed. Check provider ${task.providerId}.",
                                        ),
                                )
                        }
                    } else {
                        recovered = recovered.copy(legState = LegState.Ended(EndCause.FAILED, "Provider confirms no live leg."))
                    }
                } catch (_: Exception) {
                    recovered =
                        recovered.copy(
                            state =
                                CallTaskState.Uncertain(
                                    "Could not reattach or hang up recorded leg ${task.legId} at ${task.providerId}. Provider intervention is required; do not redial.",
                                ),
                        )
                }
            }
            journal.save(recovered)
            mutableTasks.update { it + (recovered.id to recovered) }
            recoveredLeg?.takeIf { it.state.value !is LegState.Ended }?.let { leg ->
                recoveryJobs.remove(task.id)?.cancel()
                recoveryJobs[task.id] =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val ended = leg.state.first { it is LegState.Ended }
                        val closed =
                            recovered.copy(
                                legState = ended,
                                state =
                                    CallTaskState.Interrupted("Recovered call ended after hang-up request.", uncertain),
                            )
                        journal.save(closed)
                        mutableTasks.update { it + (closed.id to closed) }
                    }
            }
        }
    }

    private data class Citation(
        val cited: String,
        val quote: Boolean,
        val items: List<String>,
    )

    companion object {
        /** How long the agent has to record an outcome after the leg ended without one. */
        const val WRAP_UP_AFTER_END_MILLIS = 30_000L

        /**
         * An entry naming a transcript item is that item. Any other entry is a quote of spoken or keyed
         * items (not SYSTEM_IVR records: tool receipts echo the agent's own arguments, which would let a
         * quote cite its earlier submission).
         *
         * Matching is on words, ignoring case, punctuation and spacing, and also ignoring breaks between consecutive numeric tokens, because the model quotes what it heard rather than the caption's
         * formatting: on a Twilio call it cited the caption "It's 22:34." as "it's 2234". A quote cites
         * the single items containing it; failing that, a run of consecutive items by the same speaker
         * it spans (a caption split mid-sentence), citing each item it touches. A leading speaker label
         * ("Remote: ...") is ignored. Anything else, a paraphrase, words from different speakers or words
         * nobody said, matches nothing, and the caller rejects the whole claim.
         */
        private fun resolveEvidence(
            transcript: List<CallTranscriptItem>,
            cited: List<String>,
        ): List<Citation> {
            val spoken = transcript.filter { it.speaker != CallSpeaker.SYSTEM_IVR }.map { it to words(it.text) }
            return cited.map { entry ->
                if (transcript.any { it.id == entry }) {
                    Citation(entry, false, listOf(entry))
                } else {
                    val quote = words(entry)
                    val unlabelled = quote.drop(1).takeIf { quote.size > 1 && quote.first() in SPEAKER_LABELS }
                    Citation(entry, true, listOfNotNull(quote, unlabelled).firstNotNullOfOrNull { quoted(spoken, it) }.orEmpty())
                }
            }
        }

        private val SPEAKER_LABELS = setOf("remote", "agent", "owner", "caller", "representative", "rep")

        /** Items [quote] cites, or null when it matches nothing. */
        private fun quoted(
            spoken: List<Pair<CallTranscriptItem, List<String>>>,
            quote: List<String>,
        ): List<String>? {
            if (quote.isEmpty()) return null
            spoken
                .filter { (_, words) -> span(words, quote) != null }
                .map { it.first.id }
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
            // Runs of consecutive items by one speaker, for a quote across a caption split.
            var start = 0
            while (start < spoken.size) {
                var end = start
                while (end + 1 < spoken.size && spoken[end + 1].first.speaker == spoken[start].first.speaker) end++
                if (end > start) {
                    val run = spoken.subList(start, end + 1)
                    val owners = run.flatMapIndexed { index, (_, words) -> List(words.size) { index } }
                    span(run.flatMap { it.second }, quote)?.let { range ->
                        return owners.subList(range.first, range.last + 1).distinct().map { run[it].first.id }
                    }
                }
                start = end + 1
            }
            return null
        }

        /** Preserve word boundaries: "now here" is not evidence for "nowhere". */
        private fun span(
            words: List<String>,
            quote: List<String>,
        ): IntRange? {
            if (quote.isEmpty() || quote.size > words.size) return null
            for (first in 0..words.size - quote.size) {
                if (words.subList(first, first + quote.size) == quote) return first until first + quote.size
            }
            return null
        }

        private fun words(text: String): List<String> =
            text
                .lowercase()
                // Captions may format a spoken reference as a clock time (22:34 vs 2234).
                // Only numeric separators are ignored; ordinary words retain their boundaries.
                .replace(Regex("(?<=\\p{N})[^\\p{L}\\p{N}]+(?=\\p{N})"), "")
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }

        /**
         * The rejection for evidence matching nothing. It shows the transcript items closest to each such
         * entry by shared words, with their IDs and exact text, because an ID always matches: the agent
         * can retry correctly in one step instead of guessing at the caption's wording again.
         */
        private fun unmatchedEvidence(
            transcript: List<CallTranscriptItem>,
            unmatched: List<String>,
        ): String {
            val spoken = transcript.filter { it.speaker != CallSpeaker.SYSTEM_IVR }
            val entries =
                unmatched.joinToString("; ") { entry ->
                    val quote = words(entry).toSet()
                    val closest =
                        spoken
                            .map { it to words(it.text).count { word -> word in quote } }
                            .filter { it.second > 0 }
                            .sortedByDescending { it.second }
                            .take(2)
                            .joinToString(", ") { (item, _) ->
                                "${item.id} (${item.speaker}): ${JsonPrimitive(item.text.take(160))}"
                            }
                    JsonPrimitive(entry).toString() +
                        if (closest.isEmpty()) " (no transcript item shares its words)" else " (closest: $closest)"
                }
            return "No outcome was recorded. Each evidence entry must be a transcript item ID or words quoted from what " +
                "was said on this call; these match nothing: $entries. Call finish again citing the item IDs that " +
                "support your claim (an ID always matches), or words exactly as they appear in those items. Do not " +
                "cite anything that was not said."
        }

        private fun strings(value: String) = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.content }

        private fun rejected(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)

        private fun completed(
            message: String,
            data: kotlinx.serialization.json.JsonObject? = null,
        ) = ExecutionOutcome(InvocationStatus.COMPLETED, message, data)

        private fun receipt(task: CallTaskSnapshot): ExecutionOutcome {
            val status =
                when (val state = task.state) {
                    is CallTaskState.Accomplished -> InvocationStatus.COMPLETED
                    is CallTaskState.Uncertain -> InvocationStatus.UNKNOWN
                    is CallTaskState.Interrupted -> if (state.outcome != null) InvocationStatus.UNKNOWN else InvocationStatus.FAILED
                    else -> InvocationStatus.FAILED
                }
            return ExecutionOutcome(
                status,
                "Call task outcome (attributed claim, not independent verification): ${task.state}",
                buildJsonObject {
                    put("taskId", task.id)
                    put("contentTrust", "external_data")
                    put("state", Json.encodeToJsonElement(CallTaskState.serializer(), task.state))
                    put("transcriptItemRefs", JsonArray(task.transcript.map { JsonPrimitive(it.id) }))
                    put("providerTraits", Json.encodeToJsonElement(CallProviderTraits.serializer(), task.traits))
                },
            )
        }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.call

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.SecretVault
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTaskCoordinatorTest {
    private class Media : CallMedia.LocalPcm {
        override val sampleRateHz = 8_000
        override val remoteFrames = MutableSharedFlow<PcmFrame>(extraBufferCapacity = 32)
        val sent = mutableListOf<PcmFrame>()
        var cancelled = 0

        /** Completed when the queued agent audio has "played"; null plays it at once. */
        var played: CompletableDeferred<Unit>? = null

        override suspend fun sendAgentFrame(frame: PcmFrame) {
            sent += frame
        }

        override suspend fun cancelAgentAudio() {
            cancelled++
            sent.clear()
        }

        override suspend fun awaitAgentAudioPlayed() {
            played?.await()
        }
    }

    private class Leg(
        override val media: CallMedia = Media(),
        override val id: String = "task",
    ) : CallLeg {
        override val state = MutableStateFlow<LegState>(LegState.Ringing)
        override val events = MutableSharedFlow<LegEvent>()
        val dtmf = mutableListOf<String>()
        val pressedAt = mutableListOf<Long>()
        var clock: () -> Long = { 0 }
        var keyMillis = 0L
        var hangups = 0
        var ownerMicrophone = false
        val microphoneRoutes = mutableListOf<Boolean>()
        var monitoring = false

        override suspend fun sendDtmf(digits: String): String {
            dtmf += digits
            pressedAt += clock()
            delay(keyMillis)
            return "Sent as test signalling."
        }

        override suspend fun hangUp() {
            hangups++
        }

        override suspend fun setOwnerMicrophone(toRemote: Boolean) {
            ownerMicrophone = toRemote
            microphoneRoutes += toRemote
        }

        override suspend fun setOwnerMonitoring(enabled: Boolean) {
            monitoring = enabled
        }
    }

    private class Brain : CallAgentBrain {
        override val events = MutableSharedFlow<CallBrainEvent>(extraBufferCapacity = 32)
        val frames = mutableListOf<PcmFrame>()
        val context = mutableListOf<CallTranscriptItem>()
        val results = linkedMapOf<String, ExecutionOutcome>()
        var cancelled = 0
        var closed = false
        var currentFloor = CallFloor.AGENT

        override suspend fun pushRemotePcm(frame: PcmFrame) {
            frames += frame
        }

        override suspend fun appendContext(item: CallTranscriptItem) {
            context += item
        }

        override suspend fun toolResult(
            callId: String,
            outcome: ExecutionOutcome,
        ) {
            results[callId] = outcome
        }

        override suspend fun setFloor(floor: CallFloor) {
            this.currentFloor = floor
        }

        override suspend fun cancelOutput() {
            cancelled++
        }

        override suspend fun close() {
            closed = true
        }
    }

    private class Journal : CallTaskJournal {
        val records = mutableListOf<CallTaskSnapshot>()
        var failWrites = false

        override suspend fun save(snapshot: CallTaskSnapshot) {
            check(!failWrites) { "Storage failed" }
            records += snapshot
        }

        override suspend fun latest() = records.associateBy { it.id }.values.toList()
    }

    private inner class Harness(
        val test: TestScope,
        val hosted: Boolean = false,
        val detectsSpeech: Boolean = true,
        val briefCredentials: List<String> = listOf("phone/pin"),
        val listenInByDefault: Boolean = false,
        val brainGate: CompletableDeferred<Unit>? = null,
        val brainFailureAfterGate: Boolean = false,
        val vault: SecretVault = SecretVault(),
    ) {
        val saved = mutableMapOf("phone/pin" to PIN)
        val media = Media()
        val leg =
            Leg(
                if (hosted) {
                    object : CallMedia.ModelHosted {
                        override val sidebandSessionId = "sip"
                    }
                } else {
                    media
                },
            ).also { leg -> leg.clock = { test.testScheduler.currentTime } }
        val journal = Journal()
        val invocations = MemoryInvocationRepository()
        val brains = mutableListOf<Brain>()
        val opened = mutableListOf<CallBrainRequest>()
        val questions = mutableListOf<CallOwnerQuestion>()
        val answers = mutableListOf<CompletableDeferred<String>>()
        var speech = false
        var placements = 0
        var notPlaced: String? = null
        var agentProblem: String? = null
        val placedLegs = mutableListOf<Leg>()
        val reattached = mutableListOf<String>()
        val traits =
            CallProviderTraits(
                if (hosted) CallMediaKind.MODEL_HOSTED else CallMediaKind.LOCAL_PCM,
                true,
                !hosted,
                hosted,
                !hosted,
                3_600_000,
            )
        val provider =
            object : CallProvider {
                override val id = "fake"
                override val traits get() = this@Harness.traits

                override suspend fun unavailableReason(): String? = null

                override suspend fun place(request: PlaceCallRequest): CallLeg {
                    assertEquals(request.legId, journal.records.last().id)
                    assertEquals(CallTaskState.Dialing, journal.records.last().state)
                    notPlaced?.let { throw CallNotPlaced(it) }
                    placements++
                    return (if (request.legId == "task") leg else Leg(id = request.legId)).also { placedLegs += it }
                }

                override suspend fun reattach(legId: String): CallLeg {
                    reattached += legId
                    return leg
                }
            }
        val coordinator = coordinator()

        fun coordinator() =
            CallTaskCoordinator(
                CallProviderRegistry(listOf(provider)),
                brains = { request ->
                    opened += request
                    Brain().also { brains += it }.also {
                        // Simulate a factory that finishes setup after its opening job is cancelled.
                        brainGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
                        if (brainFailureAfterGate) error("Late factory failure")
                    }
                },
                journal = journal,
                invocations = invocations,
                scope = test.backgroundScope,
                askOwner = { question ->
                    questions += question
                    CompletableDeferred<String>().also { answers += it }.await()
                },
                credentials =
                    object : CallCredentialResolver {
                        override fun configured() = saved.keys.map { CallCredential(it, "Label of $it") }

                        override suspend fun resolve(reference: String) =
                            saved[reference]?.toCharArray() ?: throw CallCredentialUnavailable("No value is saved for $reference.")
                    },
                secrets = vault,
                speechDetector = { if (detectsSpeech) CallSpeechDetector { speech } else null },
                nowMillis = { 42 },
                listenInByDefault = { listenInByDefault },
                agentUnavailableReason = { agentProblem },
            )

        val ownerRegistry = CapabilityRegistry(CallCapabilities.backends(coordinator), CallCapabilities.definitions)
        val ownerDispatcher = CapabilityDispatcher(ownerRegistry, invocations)
        val start =
            ToolProposal(
                "task",
                CallCapabilities.START,
                mapOf(
                    "number" to "+18005550100",
                    "goal" to "Get a reference",
                    "briefing" to "Owner context",
                    "disclosures" to "Name only",
                    "mandate" to "[\"request-reference\"]",
                    "credentials" to Json.encodeToString(briefCredentials),
                ),
                "call",
                ownerRegistry.snapshot.revision,
                "thread",
                "turn",
            )

        fun start() = test.backgroundScope.async { ownerDispatcher.execute(start) }

        fun state() = coordinator.tasks.value.getValue("task")

        fun brain() = brains.last()

        suspend fun connect() {
            leg.state.value = LegState.Connected(42, AnsweredEvidence.INFERRED_DOWNLINK)
            test.runCurrent()
        }

        suspend fun tool(
            id: String,
            name: String,
            vararg arguments: Pair<String, String>,
        ) {
            brain().events.emit(CallBrainEvent.ToolCall(id, name, arguments.toMap()))
            test.runCurrent()
        }

        suspend fun transcript(
            id: String,
            speaker: CallSpeaker,
            text: String,
        ) {
            brain().events.emit(CallBrainEvent.Transcript(id, speaker, text))
            test.runCurrent()
        }

        suspend fun control(
            id: String,
            vararg args: Pair<String, String>,
        ) = ownerDispatcher.execute(
            start.copy(
                callId = "control:${java.util.UUID.randomUUID()}",
                capabilityId = id,
                arguments = mapOf("taskId" to "task") + args,
            ),
        )

        fun drop() {
            leg.state.value = LegState.Ended(EndCause.DROPPED)
        }
    }

    @Test fun ownerPreemptsQueuedAudioAndOnlyExplicitHandbackResumes() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.brain().events.emit(CallBrainEvent.Audio(PcmFrame(shortArrayOf(1), 8_000)))
            runCurrent()
            assertEquals(1, h.media.sent.size)
            h.coordinator.ownerSpeechStarted("task")
            assertEquals(CallFloor.OWNER, h.state().floor)
            assertTrue(h.brain().cancelled > 0)
            assertTrue(h.media.sent.isEmpty())
            assertTrue(h.leg.ownerMicrophone)
            h.coordinator.ownerTranscript("task", "owner-1", "I can confirm that")
            h.brain().events.emit(CallBrainEvent.Audio(PcmFrame(shortArrayOf(2), 8_000)))
            runCurrent()
            assertTrue(h.media.sent.isEmpty())
            assertEquals(
                CallSpeaker.OWNER_TO_REMOTE,
                h
                    .brain()
                    .context
                    .last()
                    .speaker,
            )
            assertEquals(CallFloor.OWNER, h.state().floor)
            h.control(CallCapabilities.FLOOR, "floor" to "AGENT")
            assertEquals(CallFloor.AGENT, h.state().floor)
            assertFalse(h.leg.ownerMicrophone)
        }

    @Test fun ownerControlsProceedDuringBrainStartupAndAStaleBrainIsClosed() =
        runTest {
            val takeoverGate = CompletableDeferred<Unit>()
            val takeover = Harness(this, brainGate = takeoverGate)
            takeover.start()
            runCurrent()
            takeover.connect()
            assertEquals(BrainState.DETACHED, takeover.state().brain)

            val floor = takeover.control(CallCapabilities.FLOOR, "floor" to "OWNER")
            assertEquals(InvocationStatus.COMPLETED, floor.status)
            assertTrue(takeover.leg.ownerMicrophone)
            takeover.control(CallCapabilities.INSTRUCT, "text" to "Ask for the confirmation number")
            takeoverGate.complete(Unit)
            runCurrent()
            assertEquals(BrainState.ATTACHED, takeover.state().brain)
            assertEquals(CallFloor.OWNER, takeover.brain().currentFloor)
            assertEquals(
                "Ask for the confirmation number",
                takeover
                    .brain()
                    .context
                    .single()
                    .text,
            )
            assertFalse(takeover.brain().closed)

            val cancelGate = CompletableDeferred<Unit>()
            val cancelled = Harness(this, brainGate = cancelGate)
            cancelled.start()
            runCurrent()
            cancelled.connect()
            val cancel = cancelled.control(CallCapabilities.CANCEL)
            assertEquals(InvocationStatus.COMPLETED, cancel.status)
            assertEquals(1, cancelled.leg.hangups)
            cancelGate.complete(Unit)
            runCurrent()
            assertTrue(cancelled.brain().closed)
            assertEquals(BrainState.DETACHED, cancelled.state().brain)
            assertEquals(CallFloor.MUTED, cancelled.state().floor)
        }

    @Test fun aLateFailedBrainFactoryCannotReplaceTheCancelledCallOutcome() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = Harness(this, brainGate = gate, brainFailureAfterGate = true)
            val result = h.start()
            runCurrent()
            h.connect()
            h.control(CallCapabilities.CANCEL)
            h.leg.state.value = LegState.Ended(EndCause.LOCAL_HANGUP)
            runCurrent()
            val ended = h.state().state
            assertTrue(ended is CallTaskState.Interrupted)
            assertEquals(InvocationStatus.FAILED, result.await().status)

            gate.complete(Unit)
            runCurrent()
            assertEquals(ended, h.state().state)
            assertEquals(1, h.leg.hangups)
        }

    @Test fun listeningInByDefaultStartsTheMonitorWhenTheCallConnectsAndTheCardCanStopIt() =
        runTest {
            val h = Harness(this, listenInByDefault = true)
            h.start()
            runCurrent()
            assertTrue(h.state().listening)
            assertFalse(h.leg.monitoring)
            h.connect()
            assertTrue(h.leg.monitoring)
            h.control(CallCapabilities.LISTEN, "enabled" to "false")
            assertFalse(h.leg.monitoring)
            assertFalse(h.state().listening)
        }

    @Test fun liveCaptionsAreUiStateReplacedByFinalItemsAndCutOffWhenTheCallEnds() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()

            suspend fun caption(
                id: String,
                text: String,
                state: CaptionState,
                speaker: CallSpeaker = CallSpeaker.AGENT,
            ) {
                h.brain().events.emit(CallBrainEvent.Caption(id, speaker, text, state))
                runCurrent()
            }

            fun captions() =
                h.coordinator.captions.value["task"]
                    .orEmpty()
            caption("a", "Hello", CaptionState.LIVE)
            caption("a", "Hello, this is", CaptionState.LIVE)
            assertEquals(listOf("Hello, this is"), captions().map { it.text })
            h.transcript("a", CallSpeaker.AGENT, "Hello, this is EVA.")
            assertTrue(captions().none { it.id == "a" })
            caption("b", "Your account number", CaptionState.LIVE)
            caption("b", "", CaptionState.CUT_OFF)
            assertEquals(listOf("Your account number" to true), captions().map { it.text to it.cutOff })
            caption("c", "Still talking", CaptionState.LIVE)
            caption("r", "Thanks for", CaptionState.LIVE, CallSpeaker.REMOTE)
            caption("r", "", CaptionState.DROPPED, CallSpeaker.REMOTE)
            h.leg.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
            assertEquals(listOf("b" to true, "c" to true), captions().map { it.id to it.cutOff })
            // Captions are provisional: only the finalized item reached the journal.
            assertEquals(
                listOf("a"),
                h.journal.records
                    .last()
                    .transcript
                    .map { it.id },
            )
        }

    @Test fun goodbyeAndFinishCannotInventTelephonyEnd() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.transcript("remote", CallSpeaker.REMOTE, "Your reference is XYZ")
            h.transcript("bye", CallSpeaker.AGENT, "Goodbye")
            assertTrue(h.state().legState is LegState.Connected)
            h.tool(
                "finish",
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Reference XYZ",
                "evidence" to "[\"remote\"]",
            )
            assertEquals(CallTaskState.WrappingUp, h.state().state)
            assertFalse(result.isCompleted)
            h.tool("hang", CallAgentTools.HANG_UP)
            assertEquals(1, h.leg.hangups)
            assertTrue(h.state().legState is LegState.Connected)
            h.leg.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
            assertEquals(InvocationStatus.COMPLETED, result.await().status)
            assertTrue(result.await().message.contains("attributed claim"))
        }

    @Test fun hangUpBeforeFinishWaitsForTheOutcomeInsteadOfEndingTheTaskWithoutOne() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.transcript("remote", CallSpeaker.REMOTE, "At the tone, two hours, forty-two minutes")
            h.tool("hang", CallAgentTools.HANG_UP)
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                h
                    .brain()
                    .results
                    .getValue("hang")
                    .status,
            )
            assertEquals(0, h.leg.hangups)
            h.tool(
                "finish",
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Heard 02:42 UTC",
                "evidence" to "[\"forty-two minutes\"]",
            )
            assertEquals(1, h.leg.hangups)
            assertTrue(
                h
                    .brain()
                    .results
                    .getValue("finish")
                    .message
                    .contains("Hang-up requested"),
            )
            h.leg.state.value = LegState.Ended(EndCause.LOCAL_HANGUP)
            runCurrent()
            assertTrue(h.state().state is CallTaskState.Accomplished)
            assertEquals(InvocationStatus.COMPLETED, result.await().status)
        }

    @Test fun aRemoteHangUpBeforeFinishStillLetsTheAgentRecordItsOutcome() =
        runTest {
            // On the device the hotline hung up after goodbye, before the agent called finish.
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.transcript("hours", CallSpeaker.REMOTE, "We are open nine to five")
            h.leg.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
            assertEquals(CallTaskState.WrappingUp, h.state().state)
            assertFalse(result.isCompleted)
            assertTrue(
                h
                    .brain()
                    .context
                    .last()
                    .text
                    .contains("REMOTE_HANGUP"),
            )
            assertTrue(h.state().transcript.none { it.speaker == CallSpeaker.SYSTEM_IVR })
            h.transcript("after", CallSpeaker.AGENT, "The line dropped, so I will wrap up")
            assertTrue(h.state().transcript.none { it.id == "after" })
            h.tool(
                "finish",
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Open nine to five",
                "evidence" to "[\"hours\"]",
            )
            assertTrue(h.state().state is CallTaskState.Accomplished)
            assertEquals(InvocationStatus.COMPLETED, result.await().status)
            assertEquals(0, h.leg.hangups)
        }

    @Test fun unconfirmedProviderLossDoesNotPromoteAnEarlierAccomplishmentClaim() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.transcript("reference", CallSpeaker.REMOTE, "Your reference number is 1234")
            h.tool(
                "finish",
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Received reference 1234",
                "evidence" to "[\"reference\"]",
            )
            val proposed = h.state().proposedOutcome
            assertTrue(proposed is CallTaskState.Accomplished)

            h.leg.state.value = LegState.Ended(EndCause.FAILED, "Lost the call-audio helper.", endConfirmed = false)
            runCurrent()

            val final = h.state()
            val outcome = final.state as CallTaskState.Uncertain
            assertTrue(outcome.unknown.contains("may still be active"))
            assertTrue(outcome.unknown.contains("Lost the call-audio helper."))
            assertEquals(proposed, final.proposedOutcome)
            assertEquals(InvocationStatus.UNKNOWN, result.await().status)
            assertTrue(h.brain().context.none { it.text.contains("call ended", ignoreCase = true) })
        }

    @Test fun anAgentThatNeverFinishesAfterTheEndLeavesTheTaskNotAccomplished() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.leg.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
            advanceTimeBy(CallTaskCoordinator.WRAP_UP_AFTER_END_MILLIS + 1)
            runCurrent()
            assertTrue(h.state().state is CallTaskState.NotAccomplished)
            assertTrue(result.isCompleted)
        }

    @Test fun finishEvidenceAcceptsItemIdsOrVerbatimQuotesAndRejectsQuotesMatchingNothing() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.transcript("remote", CallSpeaker.REMOTE, "Okay. Your confirmation number is  ABC-123, see you Tuesday!")
            h.transcript("agent", CallSpeaker.AGENT, "Great, ABC-123 for Tuesday. Thank you.")
            h.transcript("other", CallSpeaker.REMOTE, "Anything else?")

            suspend fun finish(
                id: String,
                vararg evidence: String,
            ) = h.tool(
                id,
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Booked Tuesday, ABC-123",
                "evidence" to JsonArray(evidence.map(::JsonPrimitive)).toString(),
            )

            finish("paraphrase", "your reference is ABC123", "anything else")
            val rejected = h.brain().results.getValue("paraphrase")
            assertEquals(InvocationStatus.NOT_EXECUTED, rejected.status)
            assertTrue(rejected.message, rejected.message.contains("\"your reference is ABC123\" (closest: remote (REMOTE):"))
            assertFalse(rejected.message, rejected.message.contains("\"anything else\""))
            assertEquals(CallTaskState.InCall, h.state().state)

            // A quote spanning two speakers' items, and a retry whose text only the earlier rejection receipt now holds.
            for (quote in listOf("see you Tuesday anything else", "your reference is ABC123")) {
                finish(quote, quote)
                assertEquals(
                    quote,
                    InvocationStatus.NOT_EXECUTED,
                    h
                        .brain()
                        .results
                        .getValue(quote)
                        .status,
                )
            }

            finish("quoted", "confirmation number is abc 123", "Tuesday", "other")
            val recorded = h.brain().results.getValue("quoted")
            assertEquals(InvocationStatus.COMPLETED, recorded.status)
            assertEquals(
                CallTaskState.Accomplished(listOf("remote", "agent", "other"), "Booked Tuesday, ABC-123"),
                h.state().proposedOutcome,
            )
            assertEquals(
                Json.parseToJsonElement(
                    """
                    [{"quote":"confirmation number is abc 123","items":["remote"]},
                     {"quote":"Tuesday","items":["remote","agent"]},
                     {"id":"other","items":["other"]}]
                    """,
                ),
                recorded.data!!["evidence"],
            )
        }

    @Test fun finishQuotesMatchHowTheModelCitesWhatItHeardButNotWhatNobodySaid() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.transcript("agreed", CallSpeaker.REMOTE, "Okay, fine, we'll cancel.")
            h.transcript("number", CallSpeaker.REMOTE, "It's 22:34.")
            h.transcript("split-1", CallSpeaker.REMOTE, "Your reference is")
            h.transcript("split-2", CallSpeaker.REMOTE, "R as in Robert, 9 9.")
            h.transcript("readback", CallSpeaker.AGENT, "That's two, two, three, four?")

            suspend fun finish(
                id: String,
                vararg evidence: String,
            ) = h.tool(
                id,
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to "Cancelled; confirmation 2234",
                "evidence" to JsonArray(evidence.map(::JsonPrimitive)).toString(),
            )

            // Words nobody said, and words from different speakers, are still rejected, naming the
            // closest items so the agent can cite their IDs.
            h.transcript("boundary", CallSpeaker.REMOTE, "It is now here")
            finish("invented", "It's 2234, cancelled for good", "22:34. That's two", "It is nowhere")
            val rejected = h.brain().results.getValue("invented")
            assertEquals(InvocationStatus.NOT_EXECUTED, rejected.status)
            assertTrue(rejected.message, rejected.message.contains("number (REMOTE): \"It's 22:34.\""))
            assertTrue(rejected.message, rejected.message.contains("\"22:34. That's two\""))
            assertNull(h.state().proposedOutcome)

            // On a Twilio call the model cited "It's 22:34." as "it's 2234"; captions also split mid-sentence.
            finish("cited", "it's 2234", "Remote: we'll cancel", "reference is R as in Robert 99")
            assertEquals(
                InvocationStatus.COMPLETED,
                h
                    .brain()
                    .results
                    .getValue("cited")
                    .status,
            )
            assertEquals(
                CallTaskState.Accomplished(listOf("number", "agreed", "split-1", "split-2"), "Cancelled; confirmation 2234"),
                h.state().proposedOutcome,
            )
        }

    @Test fun aRepeatedFinishChangesNothingAndADifferentOneReplacesTheClaim() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.transcript("number", CallSpeaker.REMOTE, "It's 22:34.")

            suspend fun finish(
                id: String,
                summary: String,
            ) = h.tool(
                id,
                CallAgentTools.FINISH,
                "outcome" to "accomplished",
                "summary" to summary,
                "evidence" to "[\"number\"]",
            )

            finish("first", "Confirmation 2234")
            finish("again", "Confirmation 2234")
            val again = h.brain().results.getValue("again")
            assertEquals(InvocationStatus.COMPLETED, again.status)
            assertTrue(again.message, again.message.contains("already recorded; nothing changed"))
            finish("corrected", "Confirmation 22:34, read back and confirmed")
            assertTrue(
                h
                    .brain()
                    .results
                    .getValue("corrected")
                    .message
                    .startsWith("Replaced your earlier outcome claim"),
            )
            assertEquals(
                CallTaskState.Accomplished(listOf("number"), "Confirmation 22:34, read back and confirmed"),
                h.state().proposedOutcome,
            )
            h.leg.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
            assertEquals(CallTaskState.Accomplished(listOf("number"), "Confirmation 22:34, read back and confirmed"), h.state().state)
        }

    @Test fun theAgentsHangUpWaitsForItsGoodbyeToPlayButAnOwnerCancelDoesNot() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.transcript("hours", CallSpeaker.REMOTE, "We are open nine to five")
            h.tool("finish", CallAgentTools.FINISH, "outcome" to "accomplished", "summary" to "Nine to five", "evidence" to "[\"hours\"]")
            val goodbye = CompletableDeferred<Unit>()
            h.media.played = goodbye
            h.tool("hang", CallAgentTools.HANG_UP)
            assertEquals(
                InvocationStatus.COMPLETED,
                h
                    .brain()
                    .results
                    .getValue("hang")
                    .status,
            )
            assertEquals(0, h.leg.hangups)
            goodbye.complete(Unit)
            runCurrent()
            assertEquals(1, h.leg.hangups)

            val owner = Harness(this)
            owner.start()
            runCurrent()
            owner.connect()
            owner.media.played = CompletableDeferred()
            owner.control(CallCapabilities.CANCEL)
            runCurrent()
            assertEquals(1, owner.leg.hangups)
        }

    @Test fun theBriefCarriesWhoTheAgentCallsFor() =
        runTest {
            val h = Harness(this)
            backgroundScope.async {
                h.ownerDispatcher.execute(
                    h.start.copy(
                        arguments =
                            h.start.arguments + ("onBehalfOf" to " Ivan Malison "),
                    ),
                )
            }
            runCurrent()
            h.connect()
            assertEquals(
                "Ivan Malison",
                h.opened
                    .single()
                    .brief.onBehalfOf,
            )
        }

    @Test fun askOwnerWithholdsResultAndKeepsBrainAndExplicitOwnerFloor() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            val brain = h.brain()
            h.tool("q", CallAgentTools.ASK_OWNER, "question" to "Which date?")
            assertEquals(CallFloor.AGENT_STALLING, h.state().floor)
            assertFalse(brain.results.containsKey("q"))
            assertTrue(h.questions.single().urgent)
            h.coordinator.ownerSpeechStarted("task")
            h.answers.single().complete("Tuesday")
            runCurrent()
            assertSame(brain, h.brain())
            assertEquals(
                "Tuesday",
                brain.results
                    .getValue("q")
                    .data
                    ?.get("answer")
                    ?.let { it.toString().trim('"') },
            )
            assertEquals(CallFloor.OWNER, h.state().floor)
        }

    @Test fun droppedPendingCommitIsUncertainAndNeverRedials() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.tool("commit", CallAgentTools.COMMIT, "commitment" to "pay 100 dollars")
            assertEquals("pay 100 dollars", h.state().pendingCommitment)
            h.drop()
            runCurrent()
            assertEquals(InvocationStatus.UNKNOWN, result.await().status)
            assertTrue((h.state().state as CallTaskState.Uncertain).unknown.contains("pay 100 dollars"))
            assertEquals(1, h.placements)
        }

    @Test fun droppedAcceptedCommitOrMidConfirmationIsUncertain() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.tool("commit", CallAgentTools.COMMIT, "commitment" to "request-reference")
            assertEquals(
                InvocationStatus.COMPLETED,
                h
                    .brain()
                    .results
                    .getValue("commit")
                    .status,
            )
            assertTrue(h.questions.isEmpty())
            h.transcript("partial", CallSpeaker.REMOTE, "Your confirmation number starts with")
            h.drop()
            runCurrent()
            assertEquals(InvocationStatus.UNKNOWN, result.await().status)
            assertTrue(h.state().state is CallTaskState.Uncertain)
        }

    @Test fun droppedConfirmationWithoutBindingAgreementStillReportsWhatIsUnknown() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.connect()
            h.tool("verify", CallAgentTools.PHASE, "phase" to "VERIFYING")
            h.transcript("partial", CallSpeaker.REMOTE, "Your reference starts with")
            h.drop()
            runCurrent()
            assertEquals(InvocationStatus.UNKNOWN, result.await().status)
            assertTrue((h.state().state as CallTaskState.Uncertain).unknown.contains("confirmation/reference"))
            assertEquals(1, h.placements)
        }

    @Test fun outOfMandateRequiresExactOwnerApproval() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.tool("commit", CallAgentTools.COMMIT, "commitment" to "subscribe")
            h.answers.single().complete("No, ask about the price")
            runCurrent()
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                h
                    .brain()
                    .results
                    .getValue("commit")
                    .status,
            )
            assertTrue(h.state().acceptedCommitments.isEmpty())
            h.tool("yes", CallAgentTools.COMMIT, "commitment" to "subscribe")
            h.answers.last().complete("approved")
            runCurrent()
            assertEquals(listOf("subscribe"), h.state().acceptedCommitments)
        }

    @Test fun brainExpiryReseedsWithoutRedialOrDuplicateTranscript() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.transcript("r1", CallSpeaker.REMOTE, "Please hold")
            val old = h.brain()
            old.events.emit(CallBrainEvent.Expired)
            runCurrent()
            assertTrue(old.closed)
            assertEquals(2, h.brains.size)
            assertEquals(
                listOf("r1"),
                h.opened
                    .last()
                    .transcript
                    .map { it.id },
            )
            h.transcript("r1", CallSpeaker.REMOTE, "Please hold")
            assertEquals(1, h.state().transcript.size)
            assertEquals(1, h.placements)
            assertTrue(h.state().legState is LegState.Connected)
            assertEquals(h.traits, h.opened.last().providerTraits)
            assertEquals(
                CallAgentTools.definitions.map { it.id }.toSet(),
                h.opened
                    .last()
                    .tools
                    .map { it.capabilityId }
                    .toSet(),
            )
        }

    @Test fun holdDetachesAndResumesWithPreRollBeforeNewSpeech() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            val old = h.brain()
            h.tool("hold", CallAgentTools.PHASE, "phase" to "HOLD")
            assertTrue(old.closed)
            assertEquals(BrainState.DETACHED, h.state().brain)
            h.media.remoteFrames.emit(PcmFrame(shortArrayOf(1, 2), 8_000))
            runCurrent()
            assertTrue(old.frames.isEmpty())
            assertEquals(1, h.brains.size)
            h.speech = true
            h.media.remoteFrames.emit(PcmFrame(shortArrayOf(3, 4), 8_000))
            runCurrent()
            assertEquals(listOf<Short>(1, 2, 3, 4), h.brain().frames.flatMap { it.samples.toList() })
            h.media.remoteFrames.emit(PcmFrame(shortArrayOf(5), 8_000))
            runCurrent()
            assertEquals(listOf<Short>(1, 2, 3, 4, 5), h.brain().frames.flatMap { it.samples.toList() })
            assertEquals(CallPhase.UNKNOWN, h.state().phase)
        }

    @Test fun holdWithoutSpeechDetectorKeepsTheBrainListening() =
        runTest {
            val h = Harness(this, detectsSpeech = false)
            h.start()
            runCurrent()
            h.connect()
            val brain = h.brain()
            h.tool("hold", CallAgentTools.PHASE, "phase" to "HOLD")
            assertFalse(brain.closed)
            h.media.remoteFrames.emit(PcmFrame(shortArrayOf(7), 8_000))
            runCurrent()
            assertEquals(listOf<Short>(7), brain.frames.flatMap { it.samples.toList() })
            assertEquals(1, h.brains.size)
        }

    @Test fun credentialDtmfIsPrivateEvenWhenProviderEchoesDigitsInTranscript() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.tool("dtmf", CallAgentTools.DTMF, "sequence" to "[\"credential:phone/pin\"]")
            h.transcript("echo", CallSpeaker.REMOTE, "You entered 90817263544")
            assertEquals(PIN, h.leg.dtmf.joinToString(""))
            assertFalse(
                h.journal.records
                    .toString()
                    .contains(PIN),
            )
            assertFalse(
                h.invocations.records
                    .toString()
                    .contains(PIN),
            )
            assertFalse(
                h
                    .brain()
                    .results
                    .toString()
                    .contains(PIN),
            )
            assertEquals(
                listOf(CallCredential("phone/pin", "Label of phone/pin")),
                h.opened
                    .single()
                    .brief.credentials,
            )
            assertFalse(h.opened.toString().contains(PIN))
            assertFalse(
                h
                    .state()
                    .transcript
                    .toString()
                    .contains(PIN),
            )
            assertFalse(
                h
                    .brain()
                    .context
                    .toString()
                    .contains(PIN),
            )
            assertTrue(h.state().transcript.any { it.speaker == CallSpeaker.DTMF && it.text == "[credential phone/pin]" })
            assertTrue(
                h
                    .brain()
                    .results
                    .toString()
                    .contains("Sent as test signalling."),
            )
        }

    @Test fun startRejectsAMissingOrNonKeypadCredentialBeforeDialing() =
        runTest {
            val missing = Harness(this, briefCredentials = listOf("bank/pin"))
            val refused = missing.start()
            runCurrent()
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.await().status)
            assertEquals("No value is saved for bank/pin. No call was placed.", refused.await().message)
            assertEquals(0, missing.placements)

            val letters = Harness(this)
            letters.saved["phone/pin"] = "908172635440x"
            val invalid = letters.start()
            runCurrent()
            assertEquals(InvocationStatus.NOT_EXECUTED, invalid.await().status)
            assertTrue(invalid.await().message, "phone/pin is not keypad keys" in invalid.await().message)
            assertFalse(invalid.await().message.contains("908172635440"))
            assertEquals(0, letters.placements)
        }

    @Test fun keypadEntersOnlyBriefCredentialsThatStillHaveValues() =
        runTest {
            val h = Harness(this, briefCredentials = emptyList())
            h.start()
            runCurrent()
            h.connect()
            h.tool("unlisted", CallAgentTools.DTMF, "sequence" to "[\"1\",\"credential:phone/pin\"]")
            assertTrue(h.leg.dtmf.isEmpty())
            assertEquals(
                "Credential phone/pin is not in this call's brief; this call was given none. No keys were sent.",
                h
                    .brain()
                    .results
                    .getValue("unlisted")
                    .message,
            )

            val removed = Harness(this)
            removed.start()
            runCurrent()
            removed.connect()
            removed.saved.clear()
            removed.tool("gone", CallAgentTools.DTMF, "sequence" to "[\"1\",\"credential:phone/pin\"]")
            assertTrue(removed.leg.dtmf.isEmpty())
            val result = removed.brain().results.getValue("gone")
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals("No value is saved for phone/pin. No keys were sent.", result.message)
        }

    @Test fun heldSecretHandleIsEnteredOnTheKeypadWhileRecordsShowOnlyItsReference() =
        runTest {
            val vault = SecretVault()
            val handle = vault.store("thread", CARD, "Card number")
            val h = Harness(this, briefCredentials = listOf(handle), vault = vault)
            h.start()
            runCurrent()
            h.connect()
            h.tool("card", CallAgentTools.DTMF, "sequence" to "[\"$handle\",\"#\"]")
            h.transcript("echo", CallSpeaker.REMOTE, "You entered $CARD")

            assertEquals("4111111111111111#", h.leg.dtmf.joinToString(""))
            assertEquals(
                listOf(CallCredential(handle, "Card number")),
                h.opened
                    .single()
                    .brief.credentials,
            )
            assertEquals(
                listOf("[credential $handle]", "#"),
                h
                    .state()
                    .transcript
                    .filter { it.speaker == CallSpeaker.DTMF }
                    .map { it.text },
            )
            assertEquals(
                "You entered [credential]",
                h
                    .state()
                    .transcript
                    .single { it.id == "echo" }
                    .text,
            )
            assertEquals(
                "Sent the whole sequence: [credential $handle], #. Sent as test signalling.",
                h
                    .brain()
                    .results
                    .getValue("card")
                    .message,
            )
            val records = listOf(h.journal.records, h.invocations.records, h.brain().results, h.brain().context, h.opened).toString()
            assertFalse(records.contains("4111111111111111"))
            assertFalse(records.contains(CARD))
        }

    @Test fun startRejectsAHandleThisConversationDoesNotHold() =
        runTest {
            val vault = SecretVault()
            val elsewhere = vault.store("other-thread", CARD, "Card number")
            for (handle in listOf(elsewhere, "secret:0123456789abcdef0123456789abcdef")) {
                val h = Harness(this, briefCredentials = listOf(handle), vault = vault)
                val refused = h.start()
                runCurrent()
                assertEquals(InvocationStatus.NOT_EXECUTED, refused.await().status)
                assertEquals(
                    "Credential $handle is not held for this conversation: it was cleared, belongs to another conversation, " +
                        "or EVA restarted. Look it up again with the password manager's tool. No call was placed.",
                    refused.await().message,
                )
                assertEquals(0, h.placements)
            }
        }

    @Test fun keypadRejectsAHandleOutsideTheBriefOrNoLongerHeld() =
        runTest {
            val vault = SecretVault()
            val unlisted = vault.store("thread", CARD, "Card number")
            val h = Harness(this, vault = vault)
            h.start()
            runCurrent()
            h.connect()
            h.tool("unlisted", CallAgentTools.DTMF, "sequence" to "[\"credential:$unlisted\"]")
            assertEquals(
                "Credential $unlisted is not in this call's brief; this call may enter only phone/pin. No keys were sent.",
                h
                    .brain()
                    .results
                    .getValue("unlisted")
                    .message,
            )

            val held = vault.store("thread", CARD, "Card number")
            val cleared = Harness(this, briefCredentials = listOf(held), vault = vault)
            cleared.start()
            runCurrent()
            cleared.connect()
            vault.retain(emptySet())
            cleared.tool("gone", CallAgentTools.DTMF, "sequence" to "[\"1\",\"$held\"]")
            assertEquals(
                "Credential $held is not held for this conversation: it was cleared, belongs to another conversation, " +
                    "or EVA restarted. Look it up again with the password manager's tool. No keys were sent.",
                cleared
                    .brain()
                    .results
                    .getValue("gone")
                    .message,
            )
            assertEquals(emptyList<String>(), h.leg.dtmf + cleared.leg.dtmf)
        }

    @Test fun keypadSequenceRunsInOrderWithWaitsAndOneResult() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            val start = testScheduler.currentTime
            h.tool("keys", CallAgentTools.DTMF, "sequence" to "[\"1\",\"wait:2s\",\"credential:phone/pin\",\"#\"]")
            assertEquals(listOf("1"), h.leg.dtmf)
            assertTrue(h.brain().results.isEmpty())
            advanceTimeBy(1_999)
            assertEquals(listOf("1"), h.leg.dtmf)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("1$PIN#", h.leg.dtmf.joinToString(""))
            assertEquals(listOf(start) + List(12) { start + 2_000 }, h.leg.pressedAt)
            val result =
                h
                    .brain()
                    .results.values
                    .single()
            assertEquals(InvocationStatus.COMPLETED, result.status)
            assertEquals("Sent the whole sequence: 1, wait 2 s, [credential phone/pin], #. Sent as test signalling.", result.message)
            assertEquals(
                listOf("1", "wait 2 s", "[credential phone/pin]", "#"),
                h
                    .state()
                    .transcript
                    .filter { it.speaker == CallSpeaker.DTMF }
                    .map { it.text },
            )
            assertFalse((h.journal.records.toString() + h.invocations.records).contains(PIN))
        }

    @Test fun ownerTakeoverStopsKeypadSequenceBetweenKeysWithTruthfulReceipt() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.leg.keyMillis = 400
            h.tool("keys", CallAgentTools.DTMF, "sequence" to "[\"1\",\"wait:5s\",\"credential:phone/pin\",\"#\"]")
            // Key 1 ends at 400 ms and the wait at 5.4 s; the fourth credential key is held at 6.9 s.
            advanceTimeBy(6_900)
            h.coordinator.ownerSpeechStarted("task")
            advanceTimeBy(60_000)
            assertEquals("19081", h.leg.dtmf.joinToString(""))
            val result =
                h
                    .brain()
                    .results.values
                    .single()
            assertEquals(InvocationStatus.FAILED, result.status)
            assertEquals(
                "Sent 1, wait 5 s. Stopped after 4 key(s) of [credential phone/pin] because the owner took over the call; " +
                    "nothing after that was sent. Sent as test signalling.",
                result.message,
            )
            assertFalse(h.state().transcript.any { it.speaker == CallSpeaker.DTMF && it.text == "#" })
        }

    @Test fun legEndingDuringAWaitStopsTheSequenceAtOnce() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.tool("keys", CallAgentTools.DTMF, "sequence" to "[\"wait:1ms\",\"1\",\"wait:60s\",\"2\"]")
            advanceTimeBy(1_000)
            h.drop()
            runCurrent()
            assertEquals(listOf("1"), h.leg.dtmf)
            val receipt =
                h.invocations.records.values
                    .single { it.capabilityId == CallAgentTools.DTMF }
            assertEquals(InvocationStatus.FAILED, receipt.status)
            assertEquals(
                "Sent wait 1 ms, 1. Stopped during wait 60 s because the call ended; nothing after that was sent. Sent as test signalling.",
                receipt.message,
            )
            assertTrue(testScheduler.currentTime < 2_000)
        }

    @Test fun malformedOrUnsupportedSequencesSendNothing() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.tool("comma", CallAgentTools.DTMF, "sequence" to "[\"1,2\"]")
            h.tool("letters", CallAgentTools.DTMF, "sequence" to "[\"1\",\"A\"]")
            assertTrue(h.leg.dtmf.isEmpty())
            assertTrue(
                h.invocations.records.values
                    .filter { it.capabilityId == CallAgentTools.DTMF }
                    .all { it.status == InvocationStatus.NOT_EXECUTED },
            )
        }

    @Test fun recoveryHangsUpHostedLegAndInterruptsLocalCommittedCall() =
        runTest {
            val hosted = Harness(this, hosted = true)
            hosted.start()
            runCurrent()
            hosted.connect()
            val recovered = hosted.coordinator()
            recovered.recover()
            assertEquals(listOf("task"), hosted.reattached)
            assertEquals(1, hosted.leg.hangups)
            assertTrue(
                recovered.tasks.value
                    .getValue("task")
                    .state is CallTaskState.Uncertain,
            )
            val local = Harness(this)
            local.start()
            runCurrent()
            local.connect()
            local.tool("commit", CallAgentTools.COMMIT, "commitment" to "request-reference")
            local.tool("q", CallAgentTools.ASK_OWNER, "question" to "Anything else?")
            val afterDeath = local.coordinator()
            afterDeath.recover()
            val interrupted =
                afterDeath.tasks.value
                    .getValue("task")
                    .state as CallTaskState.Interrupted
            assertNotNull(interrupted.outcome)
            assertEquals(
                BrainState.DETACHED,
                afterDeath.tasks.value
                    .getValue("task")
                    .brain,
            )
            assertEquals(1, local.brains.size)
            assertTrue(local.reattached.isEmpty())
        }

    @Test fun explicitCallbackSeedsAttributedTranscriptButRequiresFreshMandate() =
        runTest {
            val h = Harness(this)
            val first = h.start()
            runCurrent()
            h.connect()
            h.transcript("reference", CallSpeaker.REMOTE, "Call back with reference ABC")
            h.drop()
            runCurrent()
            first.await()
            assertEquals(1, h.placements)
            val callback =
                backgroundScope.async {
                    h.ownerDispatcher.execute(
                        h.start.copy(
                            callId = "callback",
                            turnId = "new-turn",
                            arguments = h.start.arguments + mapOf("previousTaskId" to "task", "mandate" to "[]"),
                        ),
                    )
                }
            runCurrent()
            h.placedLegs
                .last()
                .state.value = LegState.Connected(50, AnsweredEvidence.PRECISE_CALL_STATE)
            runCurrent()
            assertEquals(2, h.placements)
            assertEquals(
                listOf("reference"),
                h.opened
                    .last()
                    .transcript
                    .map { it.id },
            )
            assertEquals(
                "external_data",
                h.opened
                    .last()
                    .transcript
                    .single()
                    .contentTrust,
            )
            assertTrue(
                h.opened
                    .last()
                    .brief.mandate
                    .isEmpty(),
            )
            assertFalse(callback.isCompleted)
        }

    @Test fun listeningAndWhispersDoNotChangeLegOrFloorAndStatusIsThreadScoped() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            h.control(CallCapabilities.LISTEN, "enabled" to "true")
            h.control(CallCapabilities.INSTRUCT, "text" to "Ask for a receipt")
            assertEquals(CallFloor.AGENT, h.state().floor)
            assertTrue(h.state().listening)
            assertTrue(h.leg.monitoring)
            assertEquals(
                CallSpeaker.OWNER_TO_AGENT,
                h
                    .brain()
                    .context
                    .last()
                    .speaker,
            )
            val result =
                h.coordinator.control(
                    h.start.copy(
                        capabilityId = CallCapabilities.FLOOR,
                        threadId = "other",
                        arguments = mapOf("taskId" to "task", "floor" to "OWNER"),
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals(CallFloor.AGENT, h.state().floor)
        }

    @Test fun ownerMicrophoneStaysMutedToTheRemoteUntilTakeoverAndUntranscribedTakeoverIsNoted() =
        runTest {
            val h = Harness(this)
            h.start()
            runCurrent()
            h.connect()
            assertTrue(h.leg.microphoneRoutes.isNotEmpty())
            assertTrue(h.leg.microphoneRoutes.none { it })
            h.control(CallCapabilities.FLOOR, "floor" to "OWNER")
            assertTrue(h.leg.ownerMicrophone)
            h.control(CallCapabilities.FLOOR, "floor" to "AGENT")
            assertFalse(h.leg.ownerMicrophone)
            val note = h.state().transcript.last()
            assertEquals(CallSpeaker.SYSTEM_IVR, note.speaker)
            assertEquals(Wording.bundled.message(Wording.CALL_OWNER_UNTRANSCRIBED), note.text)
            assertEquals(note, h.brain().context.last())
            h.control(CallCapabilities.FLOOR, "floor" to "OWNER")
            h.coordinator.ownerTranscript("task", "owner-1", "My account is under my partner's name")
            h.control(CallCapabilities.FLOOR, "floor" to "AGENT")
            assertEquals(
                "owner-1",
                h
                    .state()
                    .transcript
                    .last()
                    .id,
            )
        }

    @Test fun journalFailureDuringConnectedTransitionReturnsUnknownAndRequestsHangup() =
        runTest {
            val h = Harness(this)
            val result = h.start()
            runCurrent()
            h.journal.failWrites = true
            h.connect()
            runCurrent()
            assertEquals(InvocationStatus.UNKNOWN, result.await().status)
            assertTrue(result.await().message.contains("journal storage failed"))
            assertEquals(1, h.leg.hangups)
            assertEquals(1, h.placements)
        }

    @Test fun noProviderExplainsSetupAndDoesNotClaimToCall() =
        runTest {
            assertTrue(CallProviderRegistry().unavailableReason()!!.contains("No phone call provider is set up"))
            // Decode the actual instructions; missing YAML wording must fail here rather than in a live call.
            assertTrue(Wording.bundled.message("call-agent-instructions").contains("untrusted external"))
            assertTrue((CallCapabilities.definitions + CallAgentTools.definitions).all { it.description.isNotBlank() })
        }

    @Test fun startUsesTheRequestedRouteOrTheFirstAvailableAndExplainsRefusals() =
        runTest {
            fun route(
                id: String,
                unavailable: String?,
            ) = object : CallProvider {
                override val id = id
                override val traits = CallProviderTraits(CallMediaKind.LOCAL_PCM, true, true, true, true)

                override suspend fun unavailableReason() = unavailable

                override suspend fun place(request: PlaceCallRequest) = error("not placed in this test")

                override suspend fun reattach(legId: String) = null
            }
            val registry = CallProviderRegistry(listOf(route("cellular", "Shizuku is not running."), route("whatsapp", null)))
            assertEquals("whatsapp", registry.choose(null).id)
            assertEquals("whatsapp", registry.choose("whatsapp").id)
            val unavailable = runCatching { registry.choose("cellular") }.exceptionOrNull()
            assertTrue(unavailable is CallNotPlaced)
            assertEquals("The cellular call route is unavailable: Shizuku is not running.", unavailable!!.message)
            assertEquals(
                "This phone has no 'fax' call route; it has cellular, whatsapp.",
                runCatching { registry.choose("fax") }.exceptionOrNull()!!.message,
            )
        }

    @Test fun anUnknownRouteIsRejectedBeforeAnythingIsJournaledOrDialed() =
        runTest {
            val h = Harness(this)
            val result = h.ownerDispatcher.execute(h.start.copy(arguments = h.start.arguments + ("provider" to "whatsapp")))
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals("This phone has no 'whatsapp' call route; it has fake. No call was placed.", result.message)
            assertTrue(h.journal.records.isEmpty())
            assertEquals(0, h.placements)
        }

    @Test fun aCallAgentThatCannotOpenRejectsTheStartBeforeAnythingIsDialed() =
        runTest {
            val h = Harness(this)
            h.agentProblem = "Sign in to OpenAI."
            val result = h.ownerDispatcher.execute(h.start)
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals("Sign in to OpenAI.", result.message)
            assertTrue(h.journal.records.isEmpty())
            assertEquals(0, h.placements)
            assertEquals("Sign in to OpenAI.", h.coordinator.readiness().agentProblem)
            assertEquals("fake", h.coordinator.readiness().defaultRoute)
        }

    @Test fun aProviderThatCertainlyDialedNothingEndsTheTaskWithItsReason() =
        runTest {
            val h = Harness(this)
            h.notPlaced = "No saved contact has WhatsApp for +18005550100."
            val result = h.start()
            runCurrent()
            assertEquals(InvocationStatus.FAILED, result.await().status)
            assertEquals(
                CallTaskState.NotAccomplished("No call was placed: No saved contact has WhatsApp for +18005550100."),
                h.state().state,
            )
            assertEquals(LegState.Ended(EndCause.FAILED, "No saved contact has WhatsApp for +18005550100."), h.state().legState)
            assertEquals(0, h.leg.hangups)
        }

    @Test fun preRollRetainsPartialFramesInTimeOrder() {
        val buffer = CallPreRoll(1)
        buffer.add(PcmFrame(ShortArray(6) { it.toShort() }, 8_000))
        buffer.add(PcmFrame(ShortArray(6) { (it + 6).toShort() }, 8_000))
        assertEquals((4..11).map { it.toShort() }, buffer.drain().flatMap { it.samples.toList() })
        assertTrue(buffer.drain().isEmpty())
    }

    private companion object {
        /** Long enough that no hex ID or fingerprint contains it by chance. */
        const val PIN = "90817263544"
        const val CARD = "4111 1111 1111 1111"
    }
}

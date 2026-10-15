package com.colonelpanic.eva.adapters.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.call.CallAgentBrain
import com.colonelpanic.eva.call.CallAgentTools
import com.colonelpanic.eva.call.CallBrainEvent
import com.colonelpanic.eva.call.CallCapabilities
import com.colonelpanic.eva.call.CallCredential
import com.colonelpanic.eva.call.CallCredentialResolver
import com.colonelpanic.eva.call.CallFloor
import com.colonelpanic.eva.call.CallProviderRegistry
import com.colonelpanic.eva.call.CallSpeaker
import com.colonelpanic.eva.call.CallTaskCoordinator
import com.colonelpanic.eva.call.CallTaskJournal
import com.colonelpanic.eva.call.CallTaskSnapshot
import com.colonelpanic.eva.call.CallTranscriptItem
import com.colonelpanic.eva.call.PcmFrame
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.SqliteInvocationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Places a real cellular call through [CallTaskCoordinator] with a scripted brain that sends one
 * keypad sequence as soon as the call connects, exercising the sequence executor and the phone's
 * keypad route without a model. Opt in with `evaDtmfNumber` and `evaDtmfSequence` (a JSON array of
 * steps); `evaDtmfCredential` answers credential steps and `evaDtmfHangUpSeconds` (default 45,
 * counted from the sequence's result) ends the call.
 */
@RunWith(AndroidJUnit4::class)
class CellularDtmfLiveTest {
    @Test
    fun sendsAKeypadSequenceThroughTheCallTask() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val number = arguments.getString("evaDtmfNumber")
            val sequence = arguments.getString("evaDtmfSequence")
            assumeTrue("Requires evaDtmfNumber and evaDtmfSequence", !number.isNullOrBlank() && !sequence.isNullOrBlank())
            val credential = arguments.getString("evaDtmfCredential").orEmpty()
            val references =
                Json
                    .parseToJsonElement(requireNotNull(sequence))
                    .jsonArray
                    .map { it.jsonPrimitive.content.trim() }
                    .filter { it.startsWith("credential:") }
                    .map { it.removePrefix("credential:").trim() }
                    .distinct()
            val hangUpAfter = (arguments.getString("evaDtmfHangUpSeconds") ?: "45").toLong() * 1_000
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as EvaApplication
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val database = JournalDatabase(context, DATABASE)
            val invocations = SqliteInvocationRepository(database)
            val snapshots = mutableListOf<CallTaskSnapshot>()
            val result = CompletableDeferred<ExecutionOutcome>()
            val coordinator =
                CallTaskCoordinator(
                    CallProviderRegistry(listOf(CellularCallProvider(app, checkNotNull(app.callAudioHost)))),
                    brains = { ScriptedBrain(requireNotNull(sequence), result) },
                    journal =
                        object : CallTaskJournal {
                            override suspend fun save(snapshot: CallTaskSnapshot) {
                                synchronized(snapshots) { snapshots += snapshot }
                            }

                            override suspend fun latest() = emptyList<CallTaskSnapshot>()
                        },
                    invocations = invocations,
                    scope = scope,
                    askOwner = { error("This harness has no owner to ask.") },
                    credentials =
                        object : CallCredentialResolver {
                            override fun configured() = references.map { CallCredential(it, "Live test credential") }

                            override suspend fun resolve(reference: String) = credential.toCharArray()
                        },
                    speechDetector = { null },
                )
            val registry = CapabilityRegistry(CallCapabilities.backends(coordinator), CallCapabilities.definitions)
            val taskId = "dtmf-live-${System.currentTimeMillis()}"
            val start =
                ToolProposal(
                    taskId,
                    CallCapabilities.START,
                    mapOf(
                        "number" to requireNotNull(number),
                        "goal" to "Press the scripted keypad sequence.",
                        "briefing" to "Live keypad test.",
                        "disclosures" to "None.",
                        "mandate" to "[]",
                        "credentials" to Json.encodeToString(references),
                    ),
                    "Live keypad sequence test",
                    registry.snapshot.revision,
                    "dtmf-live-thread",
                    "dtmf-live-turn",
                )
            try {
                val task = scope.async { CapabilityDispatcher(registry, invocations).execute(start) }
                val outcome = withTimeout(5 * 60_000) { result.await() }
                Log.i(TAG, "sequence ${outcome.status}: ${outcome.message}")
                delay(hangUpAfter)
                Log.i(TAG, "cancel: ${coordinator.cancel(taskId).message}")
                Log.i(TAG, "task: ${withTimeoutOrNull(30_000) { task.await() }?.message}")
                val keys =
                    synchronized(snapshots) { snapshots.last() }
                        .transcript
                        .filter { it.speaker == CallSpeaker.DTMF }
                        .map { it.text }
                Log.i(TAG, "transcript DTMF items: $keys")
                assertEquals(InvocationStatus.COMPLETED, outcome.status)
                assertTrue(outcome.message, "keypad signalling" in outcome.message)
            } finally {
                scope.cancel()
                database.close()
                context.deleteDatabase(DATABASE)
            }
        }

    /** Sends the sequence once its events are collected, then stays silent. */
    private class ScriptedBrain(
        sequence: String,
        private val result: CompletableDeferred<ExecutionOutcome>,
    ) : CallAgentBrain {
        override val events =
            flow {
                emit(CallBrainEvent.ToolCall("sequence", CallAgentTools.DTMF, mapOf("sequence" to sequence)))
                awaitCancellation()
            }

        override suspend fun pushRemotePcm(frame: PcmFrame) = Unit

        override suspend fun appendContext(item: CallTranscriptItem) = Unit

        override suspend fun toolResult(
            callId: String,
            outcome: ExecutionOutcome,
        ) {
            result.complete(outcome)
        }

        override suspend fun setFloor(floor: CallFloor) = Unit

        override suspend fun cancelOutput() = Unit

        override suspend fun close() = Unit
    }

    private companion object {
        const val TAG = "EvaCellularDtmf"
        const val DATABASE = "dtmf-live-test.db"
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallCapabilities
import com.colonelpanic.eva.call.CallCredential
import com.colonelpanic.eva.call.CallCredentialResolver
import com.colonelpanic.eva.call.CallFloor
import com.colonelpanic.eva.call.CallLeg
import com.colonelpanic.eva.call.CallPhase
import com.colonelpanic.eva.call.CallProvider
import com.colonelpanic.eva.call.CallProviderRegistry
import com.colonelpanic.eva.call.CallSpeechDetector
import com.colonelpanic.eva.call.CallTaskCoordinator
import com.colonelpanic.eva.call.CallTaskJournal
import com.colonelpanic.eva.call.CallTaskSnapshot
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegEvent
import com.colonelpanic.eva.call.LegState
import com.colonelpanic.eva.call.PlaceCallRequest
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCallAgentCoordinatorTest {
    private inner class Harness(
        val test: TestScope,
    ) {
        val wire = CallBrainHarness(test)
        val repository = MemoryInvocationRepository()
        val answer = CompletableDeferred<String>()
        var placements = 0
        var hangups = 0
        val state = MutableStateFlow<LegState>(LegState.Connected(1, AnsweredEvidence.PROVIDER_ASSERTION))
        val records = mutableListOf<CallTaskSnapshot>()
        val coordinator =
            CallTaskCoordinator(
                providers =
                    CallProviderRegistry(
                        listOf(
                            object : CallProvider {
                                override val id = "fake-cellular"
                                override val traits = wire.request.providerTraits

                                override suspend fun unavailableReason(): String? = null

                                override suspend fun reattach(legId: String): CallLeg? = null

                                override suspend fun place(request: PlaceCallRequest): CallLeg {
                                    placements++
                                    return object : CallLeg {
                                        override val id = request.legId
                                        override val media = wire.media
                                        override val state = this@Harness.state
                                        override val events = MutableSharedFlow<LegEvent>()

                                        override suspend fun sendDtmf(digits: String) = ""

                                        override suspend fun hangUp() {
                                            hangups++
                                        }

                                        override suspend fun setOwnerMicrophone(toRemote: Boolean) = Unit

                                        override suspend fun setOwnerMonitoring(enabled: Boolean) = Unit
                                    }
                                }
                            },
                        ),
                    ),
                brains = wire.factory(),
                journal =
                    object : CallTaskJournal {
                        override suspend fun save(snapshot: CallTaskSnapshot) {
                            records += snapshot
                        }

                        override suspend fun latest() = records.associateBy { it.id }.values.toList()
                    },
                invocations = repository,
                scope = test.backgroundScope,
                askOwner = { answer.await() },
                credentials =
                    object : CallCredentialResolver {
                        override fun configured() = emptyList<CallCredential>()

                        override suspend fun resolve(reference: String): CharArray = error("No secret expected")
                    },
                speechDetector = { CallSpeechDetector { false } },
            )
        val start =
            ToolProposal(
                "task",
                CallCapabilities.START,
                mapOf(
                    "number" to "+18005550100",
                    "goal" to "Reference",
                    "briefing" to "Context",
                    "disclosures" to "Name",
                    "mandate" to "[]",
                ),
                "Call the hotline",
                "catalog",
                "thread",
                "turn",
            )

        fun launch() = test.backgroundScope.async { coordinator.execute(start) }

        fun task() = coordinator.tasks.value.getValue("task")

        fun tool(ask: Boolean = false) {
            val socket = wire.sockets.last()
            wire.committed(socket)
            socket.created()
            if (ask) {
                socket.server(
                    Json
                        .parseToJsonElement(
                            """{"type":"response.function_call_arguments.done","response_id":"resp_1","call_id":"call_owner","name":"eva_tool_1","arguments":"{\"question\":\"Which date?\"}"}""",
                        ).jsonObject,
                )
            } else {
                socket.server(CallBrainSocket.fixture("response.function_call_arguments.done"))
            }
            socket.server(CallBrainSocket.fixture("response.done"))
        }
    }

    @Test fun finishAndHangUpInEitherOrderDoNotRequestAnotherResponse() =
        runTest {
            for (hangFirst in listOf(true, false)) {
                val h = Harness(this)
                h.launch()
                runCurrent()
                val socket = h.wire.sockets.single()
                h.wire.committed(socket)
                socket.server(CallBrainSocket.fixture("conversation.item.input_audio_transcription.completed"))
                socket.created()
                val tools =
                    listOf(
                        """{"type":"function_call","call_id":"finish","name":"eva_tool_5","arguments":"{\"outcome\":\"not_accomplished\",\"summary\":\"Fee waiver declined\",\"evidence\":[]}"}""",
                        """{"type":"function_call","call_id":"hang","name":"eva_tool_4","arguments":"{}"}""",
                    )
                val output = (if (hangFirst) tools.reversed() else tools).joinToString(",")
                socket.server(
                    Json
                        .parseToJsonElement(
                            """{"type":"response.done","response":{"id":"resp_1","status":"completed","output":[$output]}}""",
                        ).jsonObject,
                )
                runCurrent()
                assertEquals(1, h.hangups)
                assertEquals(1, socket.events("response.create").size)
                socket.disconnect()
                runCurrent()
                val replacement = h.wire.sockets.last()
                h.wire.committed(replacement)
                runCurrent()
                assertEquals(0, replacement.events("response.create").size)
                h.state.value = LegState.Ended(EndCause.LOCAL_HANGUP)
                runCurrent()
            }
        }

    @Test fun realBrainToolDispatchAndDisconnectReplaceOnlyTheBrainWithReceiptsReseeded() =
        runTest {
            val h = Harness(this)
            h.launch()
            runCurrent()
            val first = h.wire.sockets.single()
            h.tool()
            runCurrent()
            assertEquals(CallPhase.HUMAN, h.task().phase)
            assertEquals(
                InvocationStatus.COMPLETED,
                h.repository.records.values
                    .single()
                    .status,
            )
            assertTrue(
                first.events("conversation.item.create").any {
                    it.toString().contains("function_call_output") &&
                        it.toString().contains("COMPLETED")
                },
            )
            first.server(CallBrainSocket.fixture("conversation.item.input_audio_transcription.completed"))
            runCurrent()
            first.disconnect()
            runCurrent()
            assertEquals(2, h.wire.sockets.size)
            assertEquals(1, h.placements)
            assertEquals(0, h.hangups)
            assertTrue(h.task().legState is LegState.Connected)
            assertEquals(1, h.task().transcript.count { it.id == "remote_1" })
            val seeded =
                h.wire.sockets
                    .last()
                    .events("conversation.item.create")
                    .joinToString()
            assertTrue(seeded.contains("remote_1"))
            assertTrue(seeded.contains("call_phase"))
            assertTrue(seeded.contains("COMPLETED"))
            h.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
        }

    @Test fun disconnectDuringOwnerQuestionReseedsAnswerAndReceiptInsteadOfReplayingTool() =
        runTest {
            val h = Harness(this)
            val task = h.launch()
            runCurrent()
            h.tool(ask = true)
            runCurrent()
            assertEquals(CallFloor.AGENT_STALLING, h.task().floor)
            h.wire.sockets
                .single()
                .disconnect()
            runCurrent()
            assertFalse(task.isCompleted)
            assertEquals(1, h.wire.sockets.size)
            h.answer.complete("Tuesday")
            runCurrent()
            assertEquals(2, h.wire.sockets.size)
            assertEquals(1, h.placements)
            val seeded =
                h.wire.sockets
                    .last()
                    .events("conversation.item.create")
                    .joinToString()
            assertTrue(seeded.contains("Tuesday"))
            assertTrue(seeded.contains("call_owner"))
            assertTrue(seeded.contains("COMPLETED"))
            assertFalse(seeded.contains("function_call_output"))
            assertEquals(
                InvocationStatus.COMPLETED,
                h.repository.records.values
                    .single()
                    .status,
            )
            assertEquals(CallFloor.AGENT, h.task().floor)
            h.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
        }

    @Test fun remoteBargeInClearsProviderPlaybackWithoutChangingOwnerFloorOrCallState() =
        runTest {
            val h = Harness(this)
            h.launch()
            runCurrent()
            val socket = h.wire.sockets.single()
            h.wire.committed()
            socket.created()
            socket.server(CallBrainSocket.fixture("response.output_audio.delta"))
            runCurrent()
            assertEquals(1, h.wire.media.uplink.size)
            val clears = h.wire.media.clears
            socket.server(CallBrainSocket.fixture("input_audio_buffer.speech_started"))
            runCurrent()
            assertTrue(h.wire.media.clears > clears)
            assertTrue(
                h.wire.media.uplink
                    .isEmpty(),
            )
            assertEquals(CallFloor.AGENT, h.task().floor)
            assertTrue(h.task().legState is LegState.Connected)
            h.state.value = LegState.Ended(EndCause.REMOTE_HANGUP)
            runCurrent()
        }
}

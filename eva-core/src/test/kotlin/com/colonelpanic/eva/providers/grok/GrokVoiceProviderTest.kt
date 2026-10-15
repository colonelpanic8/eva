package com.colonelpanic.eva.providers.grok

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.PcmVoiceMedia
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.providers.CallRejection
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrokVoiceProviderTest {
    private val catalog =
        ProviderToolCatalog(
            "catalog",
            listOf(
                ProviderToolDefinition(
                    "eva.test",
                    "Test",
                    "A test tool",
                    Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
                ),
            ),
        )

    @Test fun `configuration uses xAI schema and the microphone waits for history acknowledgement`() =
        runTest {
            val h = harness(listOf(HistoryItem.User("Previous question")))
            h.receive("""{"type":"session.created","session":{"id":"session"}}""")
            val configuration =
                h.socket.sent
                    .single()
                    .jsonObject["session"]!!
                    .jsonObject
            assertEquals("eve", configuration["voice"]!!.jsonPrimitive.content)
            assertEquals("high", configuration["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(
                24000,
                configuration["audio"]!!
                    .jsonObject["input"]!!
                    .jsonObject["format"]!!
                    .jsonObject["rate"]!!
                    .jsonPrimitive.int,
            )
            assertFalse(configuration.containsKey("type"))
            h.receive("""{"type":"session.updated","session":{"id":"session"}}""")
            assertFalse(h.media.started)
            val id =
                h.socket.sent
                    .last()
                    .jsonObject["item"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            h.receive("""{"type":"conversation.item.added","item":{"id":"$id"}}""")
            assertTrue(h.media.started)
            assertEquals(
                "grok-voice-3.0",
                h.events
                    .filterIsInstance<ProviderEvent.Connected>()
                    .single()
                    .model,
            )
            h.media.microphone!!(byteArrayOf(1, 0, 2, 0))
            assertEquals("input_audio_buffer.append", h.socket.types().last())
            h.close()
        }

    @Test fun `parallel calls wait for completed response and all results before continuation`() =
        runTest {
            val h = connected()
            h.speech("first", "r1")
            h.call("r1", "c1")
            h.call("r1", "c2")
            assertTrue(h.events.none { it is ProviderEvent.ToolCallReady })
            h.done("r1")
            val calls = h.events.filterIsInstance<ProviderEvent.ToolCallReady>()
            assertEquals(2, calls.size)
            assertEquals(setOf("speech_first"), calls.map { it.call.inputId }.toSet())
            assertTrue(calls.all { it.rejection == null && it.capabilityId == "eva.test" })
            h.session.submitToolResult(CorrelatedToolResult(calls[0].call, "COMPLETED", "Done"))
            assertFalse(h.socket.types().contains("response.create"))
            h.session.submitToolResult(CorrelatedToolResult(calls[1].call, "COMPLETED", "Done"))
            assertEquals(1, h.socket.types().count { it == "response.create" })
            h.receive("""{"type":"response.created","response":{"id":"r2"}}""")
            h.done("r2")
            assertEquals(
                "speech_first",
                h.events
                    .filterIsInstance<ProviderEvent.ResponseEnded>()
                    .single()
                    .inputId,
            )
            h.close()
        }

    @Test fun `completed xAI output can omit item status but explicitly incomplete items are rejected`() =
        runTest {
            val h = connected()
            h.speech("first", "r1")
            h.receive(
                """{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"function_call","call_id":"complete","name":"eva_tool_0","arguments":"{}"},{"type":"function_call","call_id":"partial","name":"eva_tool_0","arguments":"{}","status":"incomplete"}]}}""",
            )
            val calls = h.events.filterIsInstance<ProviderEvent.ToolCallReady>().associateBy { it.call.callId }
            assertEquals(null, calls.getValue("complete").rejection)
            assertEquals(CallRejection.INCOMPLETE_CALL, calls.getValue("partial").rejection)
            h.close()
        }

    @Test fun `late transcripts and response calls retain their original speech ownership`() =
        runTest {
            val h = connected()
            h.speech("first", "r1")
            h.call("r1", "c1")
            h.done("r1")
            h.speech("second", "r2")
            h.receive("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"first","transcript":"First request"}""")
            assertEquals(
                "speech_first",
                h.events
                    .filterIsInstance<ProviderEvent.Transcript>()
                    .single()
                    .inputId,
            )
            val call = h.events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            h.session.submitToolResult(CorrelatedToolResult(call.call, "COMPLETED", "Done"))
            assertEquals("speech_first", call.call.inputId)
            assertFalse(h.socket.types().contains("response.create"))
            h.done("r2")
            h.close()
        }

    @Test fun `barge in flushes playback and rejects tools even when server reports completed`() =
        runTest {
            val h = connected()
            h.speech("first", "r1")
            h.audio("r1")
            h.receive("""{"type":"input_audio_buffer.speech_started","item_id":"second"}""")
            h.call("r1", "c1")
            h.done("r1")
            assertEquals(1, h.media.interruptions)
            assertEquals(
                CallRejection.INTERRUPTED_RESPONSE,
                h.events
                    .filterIsInstance<ProviderEvent.ToolCallReady>()
                    .single()
                    .rejection,
            )
            assertTrue(h.socket.types().contains("response.cancel"))
            assertTrue(h.socket.types().contains("conversation.item.truncate"))
            assertFalse(h.socket.types().contains("output_audio_buffer.clear"))
            h.close()
        }

    @Test fun `speech does not truncate audio already played while a tool remains pending`() =
        runTest {
            val h = connected()
            h.speech("first", "r1")
            h.audio("r1")
            h.call("r1", "pending")
            h.done("r1")
            h.media.finished("r1", true)
            runCurrent()
            h.receive("""{"type":"input_audio_buffer.speech_started","item_id":"second"}""")
            assertFalse(h.socket.types().contains("conversation.item.truncate"))
            h.close()
        }

    @Test fun `queued audio and server response completion do not confirm announcement delivery`() =
        runTest {
            val h = connected()
            assertTrue(h.session.submitContext("Task finished", true, deliveryId = "delivery"))
            h.receive("""{"type":"response.created","response":{"id":"announcement"}}""")
            h.audio("announcement")
            h.done("announcement")
            assertTrue(h.events.none { it is ProviderEvent.ContextDelivery })
            h.media.finished("announcement", true)
            runCurrent()
            assertTrue(
                h.events
                    .filterIsInstance<ProviderEvent.ContextDelivery>()
                    .single()
                    .delivered,
            )
            h.close()
        }

    @Test fun `disconnect reports announcement as undelivered and never retries a tool`() =
        runTest {
            val h = connected()
            h.session.submitContext("Update", true, deliveryId = "delivery")
            h.receive("""{"type":"response.created","response":{"id":"announcement"}}""")
            h.audio("announcement")
            h.done("announcement")
            h.close()
            assertFalse(
                h.events
                    .filterIsInstance<ProviderEvent.ContextDelivery>()
                    .single()
                    .delivered,
            )
            assertTrue(h.media.closed)
            assertEquals(1, h.socket.types().count { it == "response.create" })
        }

    @Test fun `unknown and cancelled responses cannot authorize calls and duplicate calls execute once`() =
        runTest {
            val h = connected()
            h.receive("""{"type":"response.created","response":{"id":"unowned"}}""")
            h.call("unowned", "call")
            h.done("unowned")
            h.call("unowned", "call")
            h.done("unowned")
            assertEquals(
                CallRejection.INCOMPLETE_CALL,
                h.events
                    .filterIsInstance<ProviderEvent.ToolCallReady>()
                    .single()
                    .rejection,
            )
            h.close()
        }

    @Test fun `overlapping unacknowledged request and speech fail visibly instead of guessing tool authority`() =
        runTest {
            val h = connected()
            h.session.submitContext("Update", true, deliveryId = "delivery")
            h.receive("""{"type":"input_audio_buffer.speech_started","item_id":"spoken"}""")
            h.receive("""{"type":"input_audio_buffer.committed","item_id":"spoken"}""")
            h.receive("""{"type":"response.created","response":{"id":"ambiguous"}}""")
            assertTrue(
                h.events
                    .filterIsInstance<ProviderEvent.Failure>()
                    .single()
                    .message
                    .contains("ownership"),
            )
            assertFalse(h.events.any { it is ProviderEvent.ToolCallReady })
            assertFalse(
                h.events
                    .filterIsInstance<ProviderEvent.ContextDelivery>()
                    .single()
                    .delivered,
            )
            h.close()
        }

    @Test fun `dispatcher owns an accepted action and its receipt after Grok disconnects`() =
        runTest {
            val socket = FakeSocket()
            val media = FakeMedia()
            val repository =
                com.colonelpanic.eva.capability
                    .MemoryInvocationRepository()
            val store =
                com.colonelpanic.eva.conversation
                    .MemoryConversationStore()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var executions = 0
            val backend =
                object : com.colonelpanic.eva.capability.ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): com.colonelpanic.eva.capability.ExecutionOutcome {
                        executions++
                        release.await()
                        return com.colonelpanic.eva.capability.ExecutionOutcome(
                            com.colonelpanic.eva.capability.InvocationStatus.COMPLETED,
                            "Action completed",
                        )
                    }
                }
            val registry =
                com.colonelpanic.eva.capability.CapabilityRegistry(
                    mapOf("eva.test" to backend),
                    listOf(
                        com.colonelpanic.eva.capability.CapabilityDefinition(
                            "eva.test",
                            "Test",
                            "Test action",
                            catalog.tools.single().inputSchema,
                            readOnly = false,
                        ),
                    ),
                )
            var toolName = ""
            val background =
                object : com.colonelpanic.eva.providers.ConversationProvider {
                    override suspend fun open(request: SessionOpenRequest): com.colonelpanic.eva.providers.ConversationSession =
                        error("No background access for test")
                }
            val controller =
                com.colonelpanic.eva.conversation.ThreadController(
                    registry,
                    com.colonelpanic.eva.capability
                        .CapabilityDispatcher(registry, repository),
                    repository,
                    store,
                    backgroundScope,
                    providerFactory = { background },
                    backgroundProviderFactory = { background },
                    mediaFactory = { media },
                    voiceProviderFactory = { _, _ ->
                        object : com.colonelpanic.eva.providers.ConversationProvider {
                            override suspend fun open(request: SessionOpenRequest): com.colonelpanic.eva.providers.ConversationSession {
                                toolName = "eva_tool_" + request.catalog.tools.indexOfFirst { it.capabilityId == "eva.test" }
                                return GrokVoiceSession(socket, media, GrokVoiceConfiguration(), request)
                            }
                        }
                    },
                )
            runCurrent()
            controller.connectVoice("")
            runCurrent()

            suspend fun receive(raw: String) {
                socket.incoming.send(raw)
                runCurrent()
            }
            receive("""{"type":"session.created","session":{"id":"session"}}""")
            receive("""{"type":"session.updated","session":{"id":"session"}}""")
            receive("""{"type":"input_audio_buffer.committed","item_id":"speech"}""")
            receive("""{"type":"response.created","response":{"id":"r"}}""")
            receive(
                """{"type":"response.function_call_arguments.done","response_id":"r","call_id":"call","name":"$toolName","arguments":"{}"}""",
            )
            receive("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
            assertEquals(1, executions)
            assertEquals(com.colonelpanic.eva.capability.InvocationStatus.DISPATCHING, repository.history().single().status)
            controller.disconnect()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(1, executions)
            assertEquals(com.colonelpanic.eva.capability.InvocationStatus.COMPLETED, repository.history().single().status)
            assertTrue(media.closed)
            controller.drain("Test finished")
            runCurrent()
        }

    private fun TestScope.harness(history: List<HistoryItem> = emptyList()): Harness {
        val h = Harness(this, history)
        backgroundScope.launch { h.session.events.collect { h.events += it } }
        runCurrent()
        return h
    }

    private suspend fun TestScope.connected(): Harness =
        harness().also {
            it.receive("""{"type":"session.created","session":{"id":"session"}}""")
            it.receive("""{"type":"session.updated","session":{"id":"session"}}""")
        }

    private inner class Harness(
        val scope: TestScope,
        history: List<HistoryItem>,
    ) {
        val socket = FakeSocket()
        val media = FakeMedia()
        val session =
            GrokVoiceSession(socket, media, GrokVoiceConfiguration(), SessionOpenRequest("Instructions", catalog, history = history))
        val events = mutableListOf<ProviderEvent>()

        suspend fun receive(raw: String) {
            socket.incoming.send(raw)
            scope.runCurrent()
        }

        suspend fun speech(
            item: String,
            response: String,
        ) {
            receive("""{"type":"input_audio_buffer.committed","item_id":"$item"}""")
            receive("""{"type":"response.created","response":{"id":"$response"}}""")
        }

        suspend fun call(
            response: String,
            id: String,
        ) = receive(
            """{"type":"response.function_call_arguments.done","response_id":"$response","call_id":"$id","name":"eva_tool_0","arguments":"{}"}""",
        )

        suspend fun audio(response: String) =
            receive("""{"type":"response.output_audio.delta","response_id":"$response","item_id":"audio_$response","delta":"AQACAA=="}""")

        suspend fun done(response: String) = receive("""{"type":"response.done","response":{"id":"$response","status":"completed"}}""")

        suspend fun close() {
            session.close()
            scope.runCurrent()
        }
    }

    private class FakeSocket : GrokSocket {
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        val sent = mutableListOf<JsonElement>()

        override fun send(event: String) {
            sent += Json.parseToJsonElement(event)
        }

        fun types() = sent.map { it.jsonObject["type"]!!.jsonPrimitive.content }

        override fun close() {
            incoming.close()
        }
    }

    private class FakeMedia : PcmVoiceMedia {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        private val notifications = Channel<PcmVoiceMedia.Playback>(Channel.UNLIMITED)
        override val playback = notifications.receiveAsFlow()
        var started = false
        var closed = false
        var interruptions = 0
        var microphone: ((ByteArray) -> Unit)? = null

        override suspend fun start(onAudio: (ByteArray) -> Unit) {
            started = true
            microphone = onAudio
        }

        override fun enqueue(
            responseId: String,
            audio: ByteArray,
        ) {
            assertEquals(4, audio.size)
        }

        override fun finish(responseId: String) {}

        fun finished(
            responseId: String,
            played: Boolean,
        ) {
            notifications.trySend(PcmVoiceMedia.Playback.Finished(responseId, played))
        }

        override fun interrupt(): Map<String, Long> {
            interruptions++
            return mapOf("r1" to 20)
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            controls.value = controls.value.copy(microphoneMuted = muted)
        }

        override fun setPlaybackMuted(muted: Boolean) {
            controls.value = controls.value.copy(playbackMuted = muted)
        }

        override fun close() {
            closed = true
            notifications.close()
        }
    }
}

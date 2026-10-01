package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.MemoryConversationStore
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTurnOwnershipTest {
    @Test
    fun `late calls and interleaved transcripts retain their original response and turn`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "First request")
            f.call("r1", "waiting", "test.wait")
            runCurrent()
            val first = f.turn("First request")
            f.done("r1", listOf(f.callItem("waiting", "test.wait")))
            runCurrent()
            f.speech("second", "r2", "Second request")
            val second = f.turn("Second request")
            f.call("r1", "late-read", "test.read")
            f.call("r2", "new-read", "test.read")
            f.text("r2", "Second answer")
            f.text("r1", "First answer")
            f.done("r2")
            runCurrent()
            val receipts = f.repository.history().associateBy { it.callId }
            val late = receipts.getValue("provider:session:late-read")
            val recent = receipts.getValue("provider:session:new-read")
            assertEquals(first, late.turnId)
            assertEquals(second, recent.turnId)
            assertEquals("r1", late.initiator!!.responseId)
            assertEquals("first", late.initiator.itemId)
            assertEquals("out-late-read", late.initiator.outputItemId)
            assertEquals(InitiatorKind.USER_SPEECH, late.initiator.kind)
            val messages =
                f.store
                    .items(f.thread)
                    .filterIsInstance<ThreadItem.AssistantMessage>()
                    .associateBy { it.text }
            assertEquals(first, messages.getValue("First answer").turnId)
            assertEquals(second, messages.getValue("Second answer").turnId)
            f.gate.complete(Unit)
            f.close()
        }

    @Test
    fun `fast interleaved tool results coalesce into one metadata-bound follow-up`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Read both")
            f.call("r1", "read-one", "test.read")
            runCurrent()
            f.call("r1", "read-two", "test.read")
            runCurrent()
            f.done("r1")
            runCurrent()
            val followup = f.acceptRequest("followup")
            val metadata = followup.getValue("metadata").jsonObject
            assertEquals("tool_follow_up", metadata.getValue("eva_purpose").jsonPrimitive.content)
            assertEquals("r1", metadata.getValue("eva_parent_response_id").jsonPrimitive.content)
            assertEquals("voice:first", metadata.getValue("eva_input_id").jsonPrimitive.content)
            assertEquals("user_speech", metadata.getValue("eva_initiator").jsonPrimitive.content)
            f.done("followup")
            runCurrent()
            assertEquals(
                2,
                f.media.sent.count {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content == "response.create"
                },
            )
            assertEquals(listOf("test.read", "test.read"), f.executions)
            assertEquals(
                TurnStatus.ANSWERED,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            f.close()
        }

    @Test
    fun `response done dispatches its own final calls once even when item events arrive later`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Open it")
            val call = f.callItem("one", "test.wait")
            f.done("r1", listOf(call))
            runCurrent()
            assertEquals(listOf("test.wait"), f.executions)
            assertEquals(
                TurnStatus.OPEN,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            f.media.incoming.send(
                buildJsonObject {
                    put("type", "response.output_item.done")
                    put("response_id", "r1")
                    put("item", call)
                }.toString(),
            )
            runCurrent()
            assertEquals(1, f.executions.size)
            f.gate.complete(Unit)
            runCurrent()
            f.acceptRequest("follow-up")
            f.done("follow-up")
            runCurrent()
            assertEquals(
                TurnStatus.ANSWERED,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            assertEquals(1, f.outputs("one").size)
            assertEquals(
                "r1",
                f.repository
                    .history()
                    .single()
                    .initiator!!
                    .responseId,
            )
            f.close()
        }

    @Test
    fun `finished and unknown responses cannot borrow the current spoken requests authority`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "First request")
            val first = f.turn("First request")
            f.done("r1")
            runCurrent()
            f.speech("second", "r2", "Second request")
            f.call("r1", "late", "test.read")
            f.call("not-observed", "unknown", "test.read")
            runCurrent()
            advanceTimeBy(REALTIME_CALL_CORRELATION_MILLIS)
            runCurrent()
            assertTrue(f.executions.isEmpty())
            assertEquals(
                "NOT_EXECUTED",
                f
                    .outputs("late")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val unknown = f.outputs("unknown").single()
            assertEquals("NOT_EXECUTED", unknown.getValue("status").jsonPrimitive.content)
            assertTrue(
                unknown
                    .getValue("message")
                    .jsonPrimitive.content
                    .contains("unknown origin"),
            )
            val receipts = f.repository.history().associateBy { it.callId }
            assertEquals(first, receipts.getValue("provider:session:late").turnId)
            assertNull(receipts.getValue("provider:session:unknown").turnId)
            assertEquals(InitiatorKind.UNKNOWN, receipts.getValue("provider:session:unknown").initiator!!.kind)
            assertEquals("not-observed", receipts.getValue("provider:session:unknown").initiator!!.responseId)
            f.call("r2", "current", "test.read")
            f.done("r2")
            runCurrent()
            assertEquals(listOf("test.read"), f.executions)
            f.close()
        }

    @Test
    fun `captions before creation and late captions are bound by committed item identity`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.committed","item_id":"first"}""")
            f.raw("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"first","transcript":"First request"}""")
            runCurrent()
            f.acceptRequest("r1")
            val first = f.turn("First request")
            f.done("r1")
            f.speech("second", "r2", "Second request")
            f.raw(
                """{"type":"conversation.item.input_audio_transcription.completed",
                    "item_id":"first","transcript":"First final caption"}""",
            )
            runCurrent()
            val caption =
                f.store
                    .items(f.thread)
                    .filterIsInstance<ThreadItem.UserMessage>()
                    .last()
            assertEquals(first, caption.turnId)
            f.close()
        }

    @Test
    fun `delegated announcements have explicit origins and refuse tools without restricting new speech`() =
        runTest {
            val f = fixture()
            f.speech("request", "r1", "Research this")
            f.call("r1", "delegate", ThreadController.DEFER_TO_TEXT.capabilityId, buildJsonObject { put("task", "Research this") })
            f.done("r1")
            runCurrent()
            val turn =
                f.background.request.continuation!!
                    .turnId
            assertNotNull(
                f.background.request.continuation!!
                    .legId,
            )
            f.acceptRequest("handoff-ack")
            f.done("handoff-ack")
            runCurrent()
            f.background.incoming.send(ProviderEvent.AssistantText(turn, "Finding from a web page", false))
            f.background.incoming.send(ProviderEvent.ResponseEnded(turn, "completed"))
            runCurrent()
            assertEquals(turn, f.notifications.single().taskId)
            assertTrue(f.delivered.isEmpty())
            val announcement = f.acceptRequest("announcement")
            assertEquals("none", announcement.getValue("tool_choice").jsonPrimitive.content)
            assertEquals(
                "lifecycle_note",
                announcement
                    .getValue("metadata")
                    .jsonObject
                    .getValue("eva_purpose")
                    .jsonPrimitive.content,
            )
            f.call("announcement", "stray", "test.read")
            f.done("announcement")
            runCurrent()
            assertTrue(f.executions.isEmpty())
            val refused = f.repository.history().single()
            assertEquals(InitiatorKind.LIFECYCLE_NOTE_REPLY, refused.initiator!!.kind)
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message.contains("Ask the user"))
            f.acceptRequest("announcement-refusal")
            f.done("announcement-refusal")
            runCurrent()
            assertEquals(turn, f.delivered.single().taskId)
            f.speech("next", "r2", "Look it up")
            f.call("r2", "requested", "test.read")
            f.done("r2")
            runCurrent()
            assertEquals(listOf("test.read"), f.executions)
            assertEquals(
                InitiatorKind.USER_SPEECH,
                f.repository
                    .history()
                    .last()
                    .initiator!!
                    .kind,
            )
            f.close()
        }

    @Test
    fun `a handoff starts without waiting for a sibling device task and each call keeps one result`() =
        runTest {
            val f = fixture()
            f.speech("request", "r1", "Do this on the phone and research that")
            f.call("r1", "device", CapabilityRegistry.DEVICE_TASK)
            f.call("r1", "delegate", ThreadController.DEFER_TO_TEXT.capabilityId, buildJsonObject { put("task", "Research that") })
            f.done("r1")
            runCurrent()
            assertNotNull(f.background.request.continuation)
            assertEquals(
                "HANDED_OFF",
                f
                    .outputs("delegate")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertTrue(f.outputs("device").isEmpty())
            f.gate.complete(Unit)
            runCurrent()
            assertEquals(1, f.outputs("device").size)
            assertEquals(1, f.outputs("delegate").size)
            f.close()
        }

    @Test
    fun `a long lookup in a voice turn says once that it is still working`() =
        runTest {
            val f = fixture()

            fun notes() =
                f.media.sent.map { Json.parseToJsonElement(it).jsonObject }.count {
                    it["type"]?.jsonPrimitive?.content == "response.create" &&
                        it
                            .getValue("response")
                            .jsonObject
                            .getValue("metadata")
                            .jsonObject["eva_purpose"]
                            ?.jsonPrimitive
                            ?.content == "lifecycle_note"
                }
            f.speech("request", "r1", "Look this up")
            f.call("r1", "lookup", "test.lookup")
            f.done("r1")
            advanceTimeBy(7_000)
            runCurrent()
            assertEquals(0, notes())
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, notes())
            assertEquals(
                "none",
                f
                    .acceptRequest("still-working")
                    .getValue("tool_choice")
                    .jsonPrimitive.content,
            )
            f.done("still-working")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, notes())
            assertTrue(f.outputs("lookup").isEmpty())
            f.gate.complete(Unit)
            runCurrent()
            assertEquals(1, f.outputs("lookup").size)
            f.close()
        }

    @Test
    fun `a deferred hang-up waits for another turn's slow result to be spoken`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Do the slow thing")
            f.call("r1", "slow", "test.wait")
            f.done("r1")
            runCurrent()
            f.speech("second", "r2", "That's all, bye")
            f.call("r2", "bye", ThreadController.END_CONVERSATION.capabilityId)
            f.done("r2")
            runCurrent()
            assertEquals(
                "NOT_EXECUTED",
                f
                    .outputs("bye")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            f.acceptRequest("bye-reply")
            f.text("bye-reply", "One moment, the first request is still running.")
            f.done("bye-reply")
            runCurrent()
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)

            f.gate.complete(Unit)
            runCurrent()
            assertEquals(1, f.outputs("slow").size)
            f.acceptRequest("slow-reply")
            f.text("slow-reply", "The slow thing is done. Bye.")
            f.done("slow-reply")
            runCurrent()
            assertEquals(ProviderStatus.DISCONNECTED, f.controller.state.value.providerStatus)
            f.close()
        }

    @Test
    fun `barge-in cancels running and unacknowledged responses without executing their tools`() =
        runTest {
            for (acknowledgedBeforeSpeech in listOf(false, true)) {
                val f = fixture()
                f.raw("""{"type":"input_audio_buffer.committed","item_id":"half-sentence"}""")
                runCurrent()
                if (acknowledgedBeforeSpeech) f.acceptRequest("interrupted")
                f.raw("""{"type":"input_audio_buffer.speech_started","item_id":"resumed"}""")
                f.raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"resumed"}""")
                f.raw("""{"type":"input_audio_buffer.committed","item_id":"resumed"}""")
                runCurrent()
                if (!acknowledgedBeforeSpeech) f.acceptRequest("interrupted")
                val sent = f.media.sent.map { Json.parseToJsonElement(it).jsonObject }
                assertTrue(
                    sent.any {
                        it["type"]?.jsonPrimitive?.content == "response.cancel" &&
                            it["response_id"]?.jsonPrimitive?.content == "interrupted"
                    },
                )
                assertTrue(sent.any { it["type"]?.jsonPrimitive?.content == "output_audio_buffer.clear" })
                f.done("interrupted", listOf(f.callItem("stale", "test.mutate")), "completed")
                runCurrent()
                assertTrue(f.executions.isEmpty())
                assertEquals(
                    "NOT_EXECUTED",
                    f
                        .outputs("stale")
                        .single()
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                val next = f.acceptRequest("resumed")
                assertEquals(
                    "voice:resumed",
                    next
                        .getValue("metadata")
                        .jsonObject
                        .getValue("eva_input_id")
                        .jsonPrimitive.content,
                )
                f.call("resumed", "current", "test.mutate")
                f.done("resumed")
                runCurrent()
                assertEquals(listOf("test.mutate"), f.executions)
                assertEquals(
                    "COMPLETED",
                    f
                        .outputs("current")
                        .single()
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                f.close()
            }
        }

    @Test
    fun `interrupted tool follow-ups resume on their original input after the new speech response`() =
        runTest {
            for (acknowledgedBeforeSpeech in listOf(false, true)) {
                val f = fixture()
                f.speech("first", "r1", "Read then act")
                f.call("r1", "read", "test.read")
                f.done("r1")
                runCurrent()
                val original = f.turn("Read then act")
                if (acknowledgedBeforeSpeech) f.acceptRequest("interrupted-followup")
                f.raw("""{"type":"input_audio_buffer.speech_started","item_id":"ack"}""")
                runCurrent()
                if (!acknowledgedBeforeSpeech) f.acceptRequest("interrupted-followup")
                f.done("interrupted-followup", listOf(f.callItem("stale-followup", "test.mutate")), "completed")
                runCurrent()
                assertEquals(
                    TurnStatus.OPEN,
                    f.store
                        .turns(f.thread)
                        .first { it.id == original }
                        .status,
                )
                assertEquals(
                    "NOT_EXECUTED",
                    f
                        .outputs("stale-followup")
                        .single()
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                f.speech("ack", "r2", "mm-hm")
                f.done("r2")
                runCurrent()
                val followup = f.acceptRequest("r3")
                assertEquals(
                    "voice:first",
                    followup
                        .getValue("metadata")
                        .jsonObject
                        .getValue("eva_input_id")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "r1",
                    followup
                        .getValue("metadata")
                        .jsonObject
                        .getValue("eva_parent_response_id")
                        .jsonPrimitive.content,
                )
                f.call("r3", "act", "test.mutate")
                f.done("r3")
                runCurrent()
                val receipt = f.repository.history().last()
                assertEquals(original, receipt.turnId)
                assertEquals(InvocationStatus.COMPLETED, receipt.status)
                assertEquals(listOf("test.read", "test.mutate"), f.executions)
                f.close()
            }
        }

    @Test
    fun `cancelled and incomplete responses refuse even completed zero argument items`() =
        runTest {
            val f = fixture()
            for (status in listOf("cancelled", "incomplete")) {
                f.speech(status, "r-$status", "Keep talking")
                val end = f.callItem("end-$status", ThreadController.END_CONVERSATION.capabilityId)
                f.raw(
                    buildJsonObject {
                        put("type", "response.output_item.done")
                        put("response_id", "r-$status")
                        put("item", end)
                    }.toString(),
                )
                runCurrent()
                assertTrue(f.repository.history().none { it.callId.endsWith("end-$status") })
                f.done("r-$status", listOf(end), status)
                runCurrent()
                assertEquals(
                    "NOT_EXECUTED",
                    f
                        .outputs("end-$status")
                        .single()
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            }
            f.speech("partial", "partial", "Act")
            val partial =
                JsonObject(
                    f.callItem("partial-call", "test.mutate") + mapOf("status" to kotlinx.serialization.json.JsonPrimitive("incomplete")),
                )
            f.done("partial", listOf(partial))
            runCurrent()
            assertEquals(
                "NOT_EXECUTED",
                f
                    .outputs("partial-call")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertTrue(f.executions.isEmpty())
            f.close()
        }

    @Test
    fun `a missing response id is resolved by final output before the call is answered`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Run once")
            val call = f.callItem("one", "test.mutate")
            val unbound =
                buildJsonObject {
                    put("type", "response.output_item.done")
                    put("item", call)
                }.toString()
            f.raw(unbound)
            runCurrent()
            assertTrue(f.outputs("one").isEmpty())
            f.done("r1", listOf(call))
            runCurrent()
            f.raw(unbound)
            f.done("r1", listOf(call))
            runCurrent()
            assertEquals(listOf("test.mutate"), f.executions)
            assertEquals(1, f.outputs("one").size)
            assertEquals(
                "r1",
                f.repository
                    .history()
                    .single()
                    .initiator!!
                    .responseId,
            )
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            f.close()
        }

    @Test
    fun `an already refused orphan stays refused when repeated with a response id`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Run once")
            val call = f.callItem("one", "test.mutate")
            f.raw(
                buildJsonObject {
                    put("type", "response.output_item.done")
                    put("item", call)
                }.toString(),
            )
            runCurrent()
            advanceTimeBy(REALTIME_CALL_CORRELATION_MILLIS)
            runCurrent()
            f.done("r1", listOf(call))
            runCurrent()
            assertTrue(f.executions.isEmpty())
            assertEquals(1, f.outputs("one").size)
            assertEquals(
                "NOT_EXECUTED",
                f
                    .outputs("one")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            f.close()
        }

    @Test
    fun `a device task permits a sibling mutation and its follow-up before the device result`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Work on the phone and send a message")
            f.call("r1", "device", CapabilityRegistry.DEVICE_TASK)
            f.call("r1", "message", "test.mutate")
            f.done("r1")
            runCurrent()
            assertEquals(listOf(CapabilityRegistry.DEVICE_TASK, "test.mutate"), f.executions)
            assertTrue(f.outputs("device").isEmpty())
            assertEquals(
                "COMPLETED",
                f
                    .outputs("message")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val followup = f.acceptRequest("message-reply")
            assertEquals(
                "r1",
                followup
                    .getValue("metadata")
                    .jsonObject
                    .getValue("eva_parent_response_id")
                    .jsonPrimitive.content,
            )
            f.done("message-reply")
            runCurrent()
            assertEquals(
                TurnStatus.OPEN,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            f.voice.submitContext("A task update", true, buildJsonObject { put("answer", "another task finished") })
            runCurrent()
            assertEquals(
                "none",
                f
                    .acceptRequest("announcement")
                    .getValue("tool_choice")
                    .jsonPrimitive.content,
            )
            f.done("announcement")
            f.gate.complete(Unit)
            runCurrent()
            f.acceptRequest("device-reply")
            f.done("device-reply")
            runCurrent()
            assertEquals(1, f.outputs("device").size)
            f.close()
        }

    @Test
    fun `a screen action waiting on the device does not block a message on the same thread`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Tap the blue button")
            f.call("r1", "tap", "test.tap")
            f.done("r1")
            runCurrent()
            f.speech("second", "r2", "Text Bob I'm late")
            f.call("r2", "message", "test.mutate")
            f.done("r2")
            runCurrent()
            assertEquals(listOf("test.tap", "test.mutate"), f.executions)
            assertTrue(f.outputs("tap").isEmpty())
            assertEquals(
                "COMPLETED",
                f
                    .outputs("message")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            f.close()
        }

    @Test
    fun `abandoned speech is released without shifting later response ownership`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.speech_started","item_id":"abandoned"}""")
            f.raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"abandoned"}""")
            f.raw(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"abandoned","transcript":"Do not lose this caption"}""",
            )
            runCurrent()
            f.voice.submitContext("Task update", true)
            runCurrent()
            assertTrue(
                f.media.sent.none {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content == "response.create"
                },
            )
            advanceTimeBy(REALTIME_SPEECH_WAIT_MILLIS)
            runCurrent()
            f.acceptRequest("announcement")
            f.done("announcement")
            runCurrent()
            assertTrue(
                f.store
                    .items(f.thread)
                    .filterIsInstance<ThreadItem.UserMessage>()
                    .any { it.text == "Do not lose this caption" },
            )
            f.speech("next", "r-next", "Now act")
            f.call("r-next", "next", "test.mutate")
            f.done("r-next")
            runCurrent()
            assertEquals(
                "next",
                f.repository
                    .history()
                    .single()
                    .initiator!!
                    .itemId,
            )
            assertEquals(
                f.turn("Now act"),
                f.repository
                    .history()
                    .single()
                    .turnId,
            )
            f.close()
        }

    @Test
    fun `long dictation holds replies and starts recovery only after speech stops`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.speech_started"}""")
            runCurrent()
            f.voice.submitContext("A waiting update", true)
            advanceTimeBy(REALTIME_SPEECH_WAIT_MILLIS * 2)
            runCurrent()
            assertFalse(
                f.media.sent.any {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content == "response.create"
                },
            )
            assertFalse(
                f.controller.state.value.providerMessage
                    .orEmpty()
                    .contains("did not finish"),
            )
            f.raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"dictation"}""")
            runCurrent()
            advanceTimeBy(REALTIME_SPEECH_WAIT_MILLIS - 1)
            runCurrent()
            assertFalse(
                f.media.sent.any {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content == "response.create"
                },
            )
            advanceTimeBy(1)
            runCurrent()
            assertTrue(
                f.controller.state.value.providerMessage
                    .orEmpty()
                    .contains("did not finish"),
            )
            assertEquals(
                "none",
                f
                    .acceptRequest("announcement")
                    .getValue("tool_choice")
                    .jsonPrimitive.content,
            )
            f.done("announcement")
            runCurrent()
            f.close()
        }

    @Test
    fun `late transcription failure reports only a missing caption and cannot release current speech`() =
        runTest {
            val f = fixture()
            f.speech("answered", "answered", "Already answered")
            f.done("answered")
            runCurrent()
            f.raw("""{"type":"input_audio_buffer.speech_started","item_id":"current"}""")
            f.raw(
                """{"type":"conversation.item.input_audio_transcription.failed","item_id":"answered","error":{"message":"Transcription failed"}}""",
            )
            runCurrent()
            assertEquals("The speech caption is unavailable.", f.controller.state.value.providerMessage)
            f.voice.submitContext("A waiting update", true)
            advanceTimeBy(REALTIME_SPEECH_WAIT_MILLIS * 2)
            runCurrent()
            val requests =
                f.media.sent.map { Json.parseToJsonElement(it).jsonObject }.filter {
                    it["type"]?.jsonPrimitive?.content ==
                        "response.create"
                }
            assertEquals(1, requests.size)
            f.raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"current"}""")
            f.raw("""{"type":"input_audio_buffer.committed","item_id":"current"}""")
            runCurrent()
            val next = f.acceptRequest("next")
            assertEquals(
                "voice:current",
                next
                    .getValue("metadata")
                    .jsonObject
                    .getValue("eva_input_id")
                    .jsonPrimitive.content,
            )
            f.done("next")
            runCurrent()
            f.close()
        }

    @Test
    fun `cleared and failed speech release waiting announcements immediately`() =
        runTest {
            val f = fixture()
            for ((index, event) in listOf(
                """{"type":"input_audio_buffer.cleared"}""",
                """{"type":"conversation.item.input_audio_transcription.failed","item_id":"stale"}""",
            ).withIndex()) {
                f.raw("""{"type":"input_audio_buffer.speech_started","item_id":"stale"}""")
                runCurrent()
                f.voice.submitContext("Task update", true)
                f.raw(event)
                runCurrent()
                f.acceptRequest("announcement-$index")
                f.done("announcement-$index")
                runCurrent()
            }
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            f.close()
        }

    @Test
    fun `committed speech has explicit metadata and lost response acknowledgements release the queue`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.committed","item_id":"unanswered"}""")
            runCurrent()
            val outgoing =
                f.media.sent
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .single()
            val metadata =
                outgoing
                    .getValue("response")
                    .jsonObject
                    .getValue("metadata")
                    .jsonObject
            assertEquals("unanswered", metadata.getValue("eva_speech_item_id").jsonPrimitive.content)
            f.voice.submitContext("Still available", true)
            advanceTimeBy(REALTIME_RESPONSE_ACK_TIMEOUT_MILLIS)
            runCurrent()
            f.acknowledged += outgoing.getValue("event_id").jsonPrimitive.content
            f.acceptRequest("announcement")
            f.done("announcement")
            runCurrent()
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            assertTrue(
                f.controller.state.value.providerMessage!!
                    .contains("did not acknowledge"),
            )
            f.speech("next", "next", "Continue")
            f.call("next", "act", "test.mutate")
            f.done("next")
            runCurrent()
            assertEquals(
                "next",
                f.repository
                    .history()
                    .single()
                    .initiator!!
                    .itemId,
            )
            f.close()
        }

    @Test
    fun `own request errors stay visible without disconnecting or shifting the next request`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.committed","item_id":"rejected"}""")
            runCurrent()
            val requestId =
                Json
                    .parseToJsonElement(f.media.sent.last())
                    .jsonObject
                    .getValue("event_id")
                    .jsonPrimitive.content
            f.raw(
                buildJsonObject {
                    put("type", "error")
                    put(
                        "error",
                        buildJsonObject {
                            put("event_id", requestId)
                            put("code", "invalid_request_error")
                            put("message", "Response could not be created")
                        },
                    )
                }.toString(),
            )
            runCurrent()
            f.acknowledged += requestId
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            assertEquals("Response could not be created", f.controller.state.value.providerMessage)
            f.speech("next", "r2", "Try this")
            f.call("r2", "ok", "test.read")
            f.done("r2")
            runCurrent()
            assertEquals(listOf("test.read"), f.executions)
            f.close()
        }

    @Test
    fun `idless assistant transcripts use the single active response and unowned user captions stay visible`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "My request")
            val original = f.turn("My request")
            f.raw("""{"type":"response.output_text.done","text":"Answer without a response id"}""")
            f.raw(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"unknown","transcript":"Another spoken sentence"}""",
            )
            runCurrent()
            val items = f.store.items(f.thread)
            assertEquals(original, items.filterIsInstance<ThreadItem.AssistantMessage>().single().turnId)
            assertTrue(items.filterIsInstance<ThreadItem.UserMessage>().any { it.text == "Another spoken sentence" })
            f.close()
        }

    @Test
    fun `rejected context items preserve the notification and the connected call`() =
        runTest {
            val f = fixture()
            f.speech("request", "r1", "Research")
            f.call("r1", "delegate", ThreadController.DEFER_TO_TEXT.capabilityId, buildJsonObject { put("task", "Research") })
            f.done("r1")
            runCurrent()
            f.acceptRequest("ack")
            f.done("ack")
            runCurrent()
            val turn =
                f.background.request.continuation!!
                    .turnId
            f.background.incoming.send(ProviderEvent.AssistantText(turn, "External finding", false))
            f.background.incoming.send(ProviderEvent.ResponseEnded(turn, "completed"))
            runCurrent()
            val item =
                f.media.sent.map { Json.parseToJsonElement(it).jsonObject }.last {
                    it["item"]
                        ?.jsonObject
                        ?.get("role")
                        ?.jsonPrimitive
                        ?.content == "user"
                }
            assertTrue(item.toString().contains("external_data"))
            f.raw(
                buildJsonObject {
                    put("type", "error")
                    put(
                        "error",
                        buildJsonObject {
                            put("event_id", item.getValue("event_id"))
                            put("message", "Conversation item was rejected")
                        },
                    )
                }.toString(),
            )
            runCurrent()
            assertEquals(ProviderStatus.CONNECTED, f.controller.state.value.providerStatus)
            assertEquals("Conversation item was rejected", f.controller.state.value.providerMessage)
            f.acceptRequest("announcement")
            f.done("announcement")
            runCurrent()
            assertEquals(turn, f.notifications.single().taskId)
            assertTrue(f.delivered.isEmpty())
            f.raw("""{"type":"error","error":{"code":"session_expired","message":"Session expired"}}""")
            runCurrent()
            assertEquals(ProviderStatus.DISCONNECTED, f.controller.state.value.providerStatus)
            f.close()
        }

    @Test
    fun `completed correlation history is bounded without evicting a pending input`() =
        runTest {
            val f = fixture()
            f.speech("pending", "pending", "Wait")
            f.call("pending", "pending-call", "test.wait")
            f.done("pending")
            runCurrent()
            repeat(REALTIME_CORRELATION_HISTORY + 4) { index ->
                f.speech("s-$index", "r-$index", "Read $index")
                f.call("r-$index", "c-$index", "test.read")
                f.done("r-$index")
                runCurrent()
                f.acceptRequest("follow-$index")
                f.done("follow-$index")
                runCurrent()
                f.media.sent.clear()
            }

            fun entries(name: String): Int {
                val field =
                    f.voice.javaClass
                        .getDeclaredField(name)
                        .also { it.isAccessible = true }
                return when (val value = field.get(f.voice)) {
                    is Map<*, *> -> value.size
                    is Collection<*> -> value.size
                    else -> error("Not a collection: $name")
                }
            }
            for (name in listOf("endedInputs", "retiredRequests", "itemRequests")) {
                assertTrue(
                    name,
                    entries(name) <= REALTIME_CORRELATION_HISTORY,
                )
            }
            for (name in listOf("responses", "startedInputs", "calls", "speechInputs")) {
                assertTrue(
                    name,
                    entries(name) <= REALTIME_CORRELATION_HISTORY + 1,
                )
            }
            assertEquals(0, entries("captions"))
            f.gate.complete(Unit)
            runCurrent()
            assertEquals(
                "COMPLETED",
                f
                    .outputs("pending-call")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val followup = f.acceptRequest("pending-followup")
            assertEquals(
                "voice:pending",
                followup
                    .getValue("metadata")
                    .jsonObject
                    .getValue("eva_input_id")
                    .jsonPrimitive.content,
            )
            f.done("pending-followup")
            runCurrent()
            f.close()
        }

    private suspend fun TestScope.fixture(): Fixture {
        val f = Fixture(this)
        runCurrent()
        f.controller.connectVoice("")
        runCurrent()
        f.acknowledgeCatalog()
        runCurrent()
        return f
    }

    private class Fixture(
        val test: TestScope,
    ) {
        val media = Media()
        val background = Background()
        val repository = MemoryInvocationRepository()
        val store = MemoryConversationStore()
        val gate = CompletableDeferred<Unit>()
        val executions = mutableListOf<String>()
        val notifications = mutableListOf<ThreadController.BackgroundAnswer>()
        val delivered = mutableListOf<ThreadController.BackgroundAnswer>()
        lateinit var voice: ConversationSession
        private lateinit var request: SessionOpenRequest
        val acknowledged = mutableSetOf<String>()
        private val ids = listOf("test.read", "test.lookup", "test.wait", "test.mutate", "test.tap", CapabilityRegistry.DEVICE_TASK)
        private val blocking = setOf("test.wait", "test.lookup", "test.tap", CapabilityRegistry.DEVICE_TASK)
        private val registry =
            CapabilityRegistry(
                ids.associateWith { id ->
                    object : ExecutionBackend {
                        override fun usesDeviceUi(proposal: ToolProposal): Boolean = id == "test.tap"

                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            executions += id
                            if (id in blocking) gate.await()
                            return ExecutionOutcome(InvocationStatus.COMPLETED, "Done")
                        }
                    }
                },
                ids.map {
                    CapabilityDefinition(
                        it,
                        it,
                        it,
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
                            ).jsonObject,
                        readOnly =
                            it == "test.read" || it == "test.lookup",
                    )
                },
            )
        private val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("v=0 answer".toResponseBody("application/sdp".toMediaType()))
                        .build()
                }.build()
        val controller =
            ThreadController(
                registry,
                CapabilityDispatcher(registry, repository),
                repository,
                store,
                test.backgroundScope,
                providerFactory = { background },
                mediaFactory = { media },
                backgroundProviderFactory = { background },
                onBackgroundAnswer = { notifications += it },
                onBackgroundAnswerDelivered = { delivered += it },
                voiceProviderFactory = { _, _ ->
                    object : ConversationProvider {
                        override suspend fun open(request: SessionOpenRequest): ConversationSession {
                            this@Fixture.request = request
                            return OpenAiRealtimeProvider(
                                ApiKeyAccess("test", "https://example.test"),
                                media,
                                client = client,
                                ioDispatcher = StandardTestDispatcher(test.testScheduler),
                            ).open(request).also { voice = it }
                        }
                    }
                },
            )
        val thread get() = controller.state.value.threadId!!

        suspend fun acknowledgeCatalog() =
            raw(
                buildJsonObject {
                    put("type", "session.created")
                    put(
                        "session",
                        buildJsonObject {
                            put("id", "session")
                            put(
                                "tools",
                                JsonArray(
                                    request.catalog.tools.mapIndexed { index, _ ->
                                        buildJsonObject { put("name", "eva_tool_$index") }
                                    },
                                ),
                            )
                        },
                    )
                }.toString(),
            )

        suspend fun raw(event: String) = media.incoming.send(event)

        suspend fun speech(
            item: String,
            response: String,
            text: String,
        ) {
            raw("""{"type":"input_audio_buffer.speech_started","item_id":"$item"}""")
            raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"$item"}""")
            raw("""{"type":"input_audio_buffer.committed","item_id":"$item"}""")
            test.runCurrent()
            acceptRequest(response)
            raw("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$item","transcript":"$text"}""")
            test.runCurrent()
        }

        fun callItem(
            call: String,
            capability: String,
            arguments: JsonObject = buildJsonObject {},
        ): JsonObject =
            buildJsonObject {
                put("id", "out-$call")
                put("type", "function_call")
                put("status", "completed")
                put("call_id", call)
                put("name", "eva_tool_${request.catalog.tools.indexOfFirst { it.capabilityId == capability }}")
                put("arguments", arguments.toString())
            }

        suspend fun call(
            response: String,
            call: String,
            capability: String,
            arguments: JsonObject = buildJsonObject {},
        ) = raw(
            buildJsonObject {
                put("type", "response.output_item.done")
                put("response_id", response)
                put("item", callItem(call, capability, arguments))
            }.toString(),
        )

        suspend fun done(
            response: String,
            output: List<JsonObject> = emptyList(),
            status: String = "completed",
        ) = raw(
            buildJsonObject {
                put("type", "response.done")
                put(
                    "response",
                    buildJsonObject {
                        put("id", response)
                        put("status", status)
                        put("output", JsonArray(output))
                    },
                )
            }.toString(),
        )

        suspend fun text(
            response: String,
            text: String,
        ) = raw(
            buildJsonObject {
                put("type", "response.output_text.done")
                put("response_id", response)
                put("text", text)
            }.toString(),
        )

        suspend fun turn(request: String) =
            store
                .items(thread)
                .filterIsInstance<ThreadItem.UserMessage>()
                .first { it.text == request }
                .turnId!!

        fun outputs(call: String) =
            media.sent
                .map { Json.parseToJsonElement(it).jsonObject }
                .mapNotNull { it["item"] as? JsonObject }
                .filter {
                    it["call_id"]?.jsonPrimitive?.content == call
                }.map { Json.parseToJsonElement(it.getValue("output").jsonPrimitive.content).jsonObject }

        suspend fun acceptRequest(response: String): JsonObject {
            val message =
                media.sent.map { Json.parseToJsonElement(it).jsonObject }.first {
                    it["type"]?.jsonPrimitive?.content == "response.create" &&
                        it.getValue("event_id").jsonPrimitive.content !in acknowledged
                }
            acknowledged += message.getValue("event_id").jsonPrimitive.content
            val parameters = message.getValue("response").jsonObject
            raw(
                buildJsonObject {
                    put("type", "response.created")
                    put(
                        "response",
                        buildJsonObject {
                            put("id", response)
                            put("metadata", parameters.getValue("metadata"))
                        },
                    )
                }.toString(),
            )
            test.runCurrent()
            return parameters
        }

        suspend fun close() {
            gate.complete(Unit)
            controller.drain("Test finished")
            controller.disconnect()
            test.runCurrent()
        }
    }

    private class Background :
        ConversationProvider,
        ConversationSession {
        override val connectionEpoch = "background"
        val incoming = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        lateinit var request: SessionOpenRequest

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            this.request = request
            incoming.send(ProviderEvent.Connected("background", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) = Unit

        override suspend fun requestResponse(request: ResponseRequest) = Unit

        override suspend fun submitToolResult(result: CorrelatedToolResult) = Unit

        override suspend fun close() {
            incoming.close()
        }
    }

    private class Media : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Connected(true))
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        override val eventsReady = MutableStateFlow(true)
        val sent = mutableListOf<String>()

        override suspend fun createOffer() = "v=0 offer"

        override suspend fun acceptAnswer(sdp: String) = Unit

        override fun send(event: String) {
            sent += event
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            controls.value = controls.value.copy(microphoneMuted = muted)
        }

        override fun setPlaybackMuted(muted: Boolean) = Unit

        override fun close() = Unit
    }
}

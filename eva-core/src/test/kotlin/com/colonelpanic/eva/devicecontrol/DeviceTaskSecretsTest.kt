package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.SecretVault
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.kind
import com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent
import com.colonelpanic.eva.devicecontrol.worker.WorkerCall
import com.colonelpanic.eva.devicecontrol.worker.WorkerModel
import com.colonelpanic.eva.devicecontrol.worker.WorkerReply
import com.colonelpanic.eva.devicecontrol.worker.WorkerRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTaskSecretsTest {
    private val vault = SecretVault()
    private val requests = mutableListOf<WorkerRequest>()
    private val performed = mutableListOf<Action>()
    private var field = ""
    private var sequence = 0

    private val phone =
        object : DeviceBackend {
            override suspend fun observe() =
                Observation(
                    "o${++sequence}",
                    "now",
                    "fake",
                    screen = Screen(100, 200, Orientation.PORTRAIT),
                    elements = listOf(Element(0, Role.EDIT_TEXT, field, bounds = Bounds(0, 0, 100, 50), editable = true, depth = 0)),
                )

            override suspend fun perform(action: Action): ActionResult {
                performed += action
                field = (action as SetText).text
                return ActionResult(action.actionId, action.kind, true, "t0", "t1", observe(), SetTextDetails(0, true, null, null))
            }
        }

    private fun coordinator(handle: String) =
        DeviceTaskCoordinator { thread ->
            var turn = 0
            TextTaskAgent(
                phone,
                WorkerModel { request ->
                    requests += request
                    val call =
                        if (turn++ == 0) {
                            WorkerCall(
                                "type",
                                "set_text",
                                Json
                                    .parseToJsonElement(
                                        """{"element":0,"text":null,"secret":"$handle","replace":true,"intent":"enter code"}""",
                                    ).jsonObject,
                            )
                        } else {
                            WorkerCall("done", "finish", Json.parseToJsonElement("""{"status":"completed","summary":"done"}""").jsonObject)
                        }
                    WorkerReply(listOf(call))
                },
                workerWording(Wording.bundled),
                secrets = vault.scoped(thread),
            )
        }

    private fun proposal(goal: String) =
        ToolProposal("call", CapabilityRegistry.DEVICE_TASK, mapOf("goal" to goal), goal, "catalog", "thread", "turn")

    @Test
    fun `a device task types a handle its conversation received without the worker or receipt seeing the value`() =
        runTest {
            val handle = vault.store("thread", "481516", "Current one-time code for example.com")

            val outcome = coordinator(handle).execute(proposal("Enter the code $handle in the verification field"))

            val typed = performed.single() as SetText
            assertEquals(listOf<Any>("481516", true), listOf(typed.text, typed.sensitive))
            val seen = requests.joinToString { request -> request.messages.joinToString { it.text + it.call?.arguments } }
            assertTrue(seen.contains(handle))
            assertTrue(seen.contains(SecretValues.PLACEHOLDER))
            assertFalse(seen.contains("481516"))
            val receipt = outcome.message + outcome.data
            assertFalse(receipt.contains("481516"))
            val step =
                outcome.data!!
                    .getValue("steps")
                    .jsonArray
                    .first()
                    .jsonObject
            assertTrue(step.toString().contains("secret \\\"Current one-time code for example.com\\\""))
        }

    @Test
    fun `a handle from another conversation is refused before input and the worker is told why`() =
        runTest {
            val handle = vault.store("other thread", "481516", "Current one-time code for example.com")

            coordinator(handle).execute(proposal("Enter the code $handle"))

            assertTrue(performed.isEmpty())
            val note = workerWording(Wording.bundled).note("secret_unavailable")
            assertTrue(requests[1].messages.any { note in it.text })
        }
}

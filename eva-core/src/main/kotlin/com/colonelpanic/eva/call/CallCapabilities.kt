package com.colonelpanic.eva.call

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object CallCapabilities {
    const val START = "eva.call.start"
    const val STATUS = "eva.call.status"
    const val INSTRUCT = "eva.call.instruct"
    const val FLOOR = "eva.call.floor"
    const val LISTEN = "eva.call.listen"
    const val CANCEL = "eva.call.cancel"
    val controls = setOf(STATUS, INSTRUCT, FLOOR, LISTEN, CANCEL)

    val definitions =
        listOf(
            definition(
                START,
                "Delegate a phone call",
                listOf("number", "goal", "onBehalfOf", "briefing", "disclosures", "mandate", "credentials", "previousTaskId", "provider"),
                optional = setOf("onBehalfOf", "credentials", "previousTaskId", "provider"),
                arrays = setOf("mandate", "credentials"),
            ),
            definition(STATUS, "Call task status", listOf("taskId"), optional = setOf("taskId"), readOnly = true),
            definition(INSTRUCT, "Whisper to the call agent", listOf("taskId", "text")),
            definition(
                FLOOR,
                "Take over or hand back a call",
                listOf("taskId", "floor"),
                enums = mapOf("floor" to listOf("OWNER", "AGENT", "MUTED")),
            ),
            definition(LISTEN, "Monitor a call", listOf("taskId", "enabled"), booleans = setOf("enabled")),
            definition(CANCEL, "Hang up and cancel a call task", listOf("taskId")),
        )

    fun backends(coordinator: CallTaskCoordinator): Map<String, ExecutionBackend> =
        definitions.associate { definition ->
            definition.id to
                object : ExecutionBackend {
                    override suspend fun unavailableReason() = if (definition.id == START) coordinator.unavailableReason() else null

                    override suspend fun execute(arguments: Map<String, String>) =
                        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A call task needs an owning conversation turn.")

                    override suspend fun execute(proposal: ToolProposal) =
                        if (definition.id == START) coordinator.execute(proposal) else coordinator.control(proposal)
                }
        }

    internal fun definition(
        id: String,
        title: String,
        fields: List<String>,
        optional: Set<String> = emptySet(),
        arrays: Set<String> = emptySet(),
        booleans: Set<String> = emptySet(),
        enums: Map<String, List<String>> = emptyMap(),
        readOnly: Boolean = false,
    ): CapabilityDefinition =
        tool(
            id,
            title,
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        fields.forEach { name ->
                            put(
                                name,
                                buildJsonObject {
                                    put(
                                        "type",
                                        when (name) {
                                            in arrays -> "array"
                                            in booleans -> "boolean"
                                            else -> "string"
                                        },
                                    )
                                    if (name in
                                        arrays
                                    ) {
                                        put("items", Json.parseToJsonElement("""{"type":"string","minLength":1}""").jsonObject)
                                    }
                                    if (name !in arrays && name !in booleans) put("minLength", 1)
                                    enums[name]?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                                },
                            )
                        }
                    },
                )
                put("required", JsonArray(fields.filter { it !in optional }.map(::JsonPrimitive)))
                put("additionalProperties", false)
            },
            readOnly = readOnly,
        )
}

object CallAgentTools {
    const val DTMF = "eva.call.agent.dtmf"
    const val ASK_OWNER = "eva.call.agent.ask_owner"
    const val COMMIT = "eva.call.agent.commit"
    const val PHASE = "eva.call.agent.report_phase"
    const val KEEP_LISTENING = "eva.call.agent.keep_listening"
    const val HANG_UP = "eva.call.agent.hang_up"
    const val FINISH = "eva.call.agent.finish"

    val definitions =
        listOf(
            CallCapabilities
                .definition(DTMF, "Press keypad keys", listOf("sequence"), arrays = setOf("sequence"))
                .copy(validateOperation = { args ->
                    runCatching {
                        DtmfStep.parse(Json.parseToJsonElement(args.getValue("sequence")).jsonArray.map { it.jsonPrimitive.content })
                    }.exceptionOrNull()?.let { "${it.message} No keys were sent." }
                }),
            CallCapabilities.definition(ASK_OWNER, "Ask the owner", listOf("question")),
            CallCapabilities.definition(COMMIT, "Request authority to agree", listOf("commitment")),
            CallCapabilities.definition(
                PHASE,
                "Report conversation phase",
                listOf("phase"),
                enums = mapOf("phase" to CallPhase.entries.map { it.name }),
            ),
            CallCapabilities.definition(HANG_UP, "Hang up the leg", emptyList()),
            CallCapabilities.definition(
                FINISH,
                "Report a call outcome claim",
                listOf("outcome", "summary", "evidence"),
                arrays = setOf("evidence"),
                enums = mapOf("outcome" to listOf("accomplished", "not_accomplished", "uncertain")),
            ),
            CallCapabilities.definition(KEEP_LISTENING, "Stay silent and keep listening", emptyList(), readOnly = true),
        )
}

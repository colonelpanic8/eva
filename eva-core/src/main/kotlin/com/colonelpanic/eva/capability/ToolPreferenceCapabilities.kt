package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Where the `tools` configuration group is kept; writes go to the user's portable configuration. */
interface ToolPreferenceStore {
    fun preferences(): Map<String, ToolPreferences>

    /** Null removes the capability's entry. */
    fun save(
        capabilityId: String,
        preferences: ToolPreferences?,
    )
}

/** Lets the user save, change, or clear an action's default arguments and confirmation level by asking. */
object ToolPreferenceCapabilities {
    const val SET = "eva.tools.preferences"
    private const val INHERIT = "declared"

    val definition =
        tool(
            SET,
            "Set an action's defaults",
            Json
                .parseToJsonElement(
                    """
                    {"type":"object","properties":{
                    "capabilityId":{"type":"string","minLength":1,"maxLength":200},
                    "defaults":{"type":"object","additionalProperties":{"type":"string","maxLength":4000},"maxProperties":64},
                    "clear":{"type":"array","items":{"type":"string","maxLength":64},"maxItems":64},
                    "confirm":{"type":"string","enum":["never","unresolved","always","$INHERIT"]}},
                    "required":["capabilityId"],"additionalProperties":false}
                    """,
                ).jsonObject,
        )

    fun backend(
        store: ToolPreferenceStore,
        catalog: () -> Map<String, CapabilityDefinition>,
    ): ExecutionBackend =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                val id = arguments.getValue("capabilityId")
                val target =
                    catalog()[id]
                        ?: return ExecutionOutcome(
                            InvocationStatus.NOT_EXECUTED,
                            "No enabled action has the ID $id. Use the exact tool name the action is offered under. Nothing was saved.",
                        )
                val properties = (target.inputSchema["properties"] as? JsonObject).orEmpty()
                val defaults =
                    arguments["defaults"]
                        ?.let { text ->
                            (Json.parseToJsonElement(text) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
                        }.orEmpty()
                val clear =
                    arguments["clear"]
                        ?.let { text -> (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
                        .orEmpty()
                val unknown = (defaults.keys + clear).filter { it !in properties || it.startsWith("_") }
                if (unknown.isNotEmpty()) {
                    return ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "${target.title} has no parameter named ${unknown.joinToString()}. " +
                            "Its parameters are ${properties.keys.joinToString()}. Nothing was saved.",
                    )
                }
                val current = store.preferences()[id] ?: ToolPreferences()
                val confirm =
                    when (val level = arguments["confirm"]) {
                        null -> current.confirm
                        INHERIT -> null
                        else -> ConfirmLevel.of(level)
                    }
                val updated = ToolPreferences(confirm, (current.defaults - clear.toSet() + defaults).toSortedMap())
                val changed = updated != current
                if (changed) store.save(id, updated.takeUnless { it.confirm == null && it.defaults.isEmpty() })
                val listed =
                    updated.defaults.entries
                        .joinToString("; ") { "${it.key} = ${it.value}" }
                        .ifEmpty { "none" }
                val level = updated.confirm?.wire ?: (target.confirmation?.level?.wire ?: ConfirmLevel.NEVER.wire) + " (declared)"
                return ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    (if (changed) "Saved. " else "Unchanged. ") + "${target.title} defaults: $listed. Confirmation: $level.",
                    buildJsonObject {
                        put("capabilityId", id)
                        put("defaults", JsonObject(updated.defaults.mapValues { JsonPrimitive(it.value) }))
                        put("confirm", level)
                    },
                )
            }
        }
}

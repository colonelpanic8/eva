package com.colonelpanic.eva.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CapabilityRegistry(
    backends: Map<String, ExecutionBackend>,
    definitions: List<CapabilityDefinition> = BundledCapabilities.definitions,
) {
    private val bindings = backends.toMap()
    val catalog = definitions.filter { it.id in bindings }.toList()
    private val descriptions = catalog.associateBy { it.id }

    init {
        require(descriptions.size == catalog.size) { "Duplicate capability IDs" }
        require(bindings.keys == descriptions.keys) { "Every backend needs a capability definition" }
        catalog.forEach { ToolSchema.check(it.inputSchema) }
    }

    fun resolve(proposal: ToolProposal): ExecutionBackend? =
        if (proposal.catalogRevision == REVISION) bindings[proposal.capabilityId] else null

    fun validationError(proposal: ToolProposal): String? {
        if (resolve(proposal) == null) return "This action is unavailable. No app was opened."
        val definition = descriptions.getValue(proposal.capabilityId)
        return ToolSchema.error(definition.inputSchema, JsonObject(proposal.arguments.mapValues { JsonPrimitive(it.value) }))
            ?: definition.validateOperation(proposal.arguments)
    }

    companion object {
        const val MAP_SEARCH = "eva.android.maps.search"
        const val NAVIGATE = "eva.android.maps.navigate"
        const val SMS_COMPOSE = "eva.android.messages.compose"
        const val REVISION = 2
        const val MAX_DESTINATION_LENGTH = 500
    }
}

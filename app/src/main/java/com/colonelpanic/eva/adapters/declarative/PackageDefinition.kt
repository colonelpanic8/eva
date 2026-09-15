package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionSemantics
import com.colonelpanic.eva.capability.extensions.Effect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PackageDefinition(
    val id: String,
    val version: String,
    val title: String,
    val capabilities: List<PackageCapability>,
    val digest: String,
    val document: JsonObject,
    val androidPackages: List<String> = emptyList(),
)

enum class PackageEffect { READ, WRITE, HANDOFF, UNKNOWN }

fun PackageEffect.toEffect(): Effect =
    when (this) {
        PackageEffect.READ -> Effect.READ
        PackageEffect.WRITE -> Effect.WRITE
        PackageEffect.HANDOFF -> Effect.HANDOFF
        PackageEffect.UNKNOWN -> Effect.UNKNOWN
    }

fun PackageDefinition.appTargets(): List<String> {
    fun targets(binding: DeclarativeBinding): List<String> =
        when (binding) {
            is DeclarativeBinding.Intent -> listOfNotNull(binding.targetPackage)
            is DeclarativeBinding.Select -> targets(binding.present) + targets(binding.absent)
            else -> emptyList()
        }
    return (androidPackages + capabilities.flatMap { targets(it.binding) }).distinct()
}

data class PackageCapability(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: PackageEffect,
    val execution: ExecutionSemantics,
    val binding: DeclarativeBinding,
    val validators: Map<String, String> = emptyMap(),
    val receipts: ReceiptText = ReceiptText(),
    val outputSchema: JsonObject? = null,
    val annotations: JsonObject? = null,
)

data class ReceiptText(
    val success: String? = null,
    val handlerMissing: String? = null,
)

/** A slot's type is a scalar, or `array` for a scalar list that only a JSON request body can carry. */
sealed interface ScalarSlot {
    val type: String

    data class Argument(
        val name: String,
        override val type: String,
        val default: JsonElement? = null,
        val required: Boolean = false,
    ) : ScalarSlot

    data class Literal(
        val value: JsonPrimitive,
        override val type: String,
    ) : ScalarSlot
}

sealed interface BodyValue {
    data class Scalar(
        val slot: ScalarSlot,
    ) : BodyValue

    data class Fields(
        val fields: Map<String, BodyValue>,
    ) : BodyValue
}

sealed interface DeclarativeBinding {
    data class Select(
        val argument: String,
        val present: DeclarativeBinding,
        val absent: DeclarativeBinding,
    ) : DeclarativeBinding

    data class Intent(
        val action: String,
        val uriBase: String,
        val query: Map<String, ScalarSlot>,
        val extras: Map<String, ScalarSlot>,
        val targetPackage: String?,
        val mimeType: String? = null,
        val packageByName: String? = null,
        val opaque: ScalarSlot? = null,
        val targetClass: String? = null,
    ) : DeclarativeBinding

    data class Content(
        val uri: String,
        val authority: String,
        val projection: Map<String, String>,
        val selection: List<Predicate>,
        val maxRows: Int,
        val maxBytes: Int,
        val query: Map<String, ScalarSlot> = emptyMap(),
        val path: Map<String, ScalarSlot> = emptyMap(),
    ) : DeclarativeBinding

    data class Http(
        val origin: String,
        val method: String,
        val path: String,
        val parameters: List<Parameter>,
        val requestBody: BodyValue.Fields?,
        val credential: String?,
        val maxResponseBytes: Int,
        val result: ResultProjection,
    ) : DeclarativeBinding
}

data class Predicate(
    val column: String,
    val operator: String,
    val slot: ScalarSlot,
)

data class Parameter(
    val location: String,
    val name: String,
    val slot: ScalarSlot,
)

data class ResultProjection(
    val pointer: String,
    val maxBytes: Int,
    val evidence: Evidence?,
    val items: ItemProjection? = null,
    val notExecutedStatuses: Set<Int> = emptySet(),
)

data class ItemProjection(
    val arrayPaths: List<String>,
    val line: String,
    val fields: Map<String, ItemField>,
    val maxItems: Int,
    val truncationNote: String,
    val totalPointer: String?,
    val filter: ItemFilter? = null,
)

data class ItemField(
    val pointer: String,
    val type: String,
    val required: Boolean,
)

data class Evidence(
    val pointer: String,
    val expected: JsonElement,
)

data class ItemFilter(
    val fields: List<String>,
    val argument: String,
)

fun PackageDefinition.contentBindings(): List<DeclarativeBinding.Content> {
    fun leaves(binding: DeclarativeBinding): List<DeclarativeBinding.Content> =
        when (binding) {
            is DeclarativeBinding.Content -> listOf(binding)
            is DeclarativeBinding.Select -> leaves(binding.present) + leaves(binding.absent)
            else -> emptyList()
        }
    return capabilities.flatMap { leaves(it.binding) }
}

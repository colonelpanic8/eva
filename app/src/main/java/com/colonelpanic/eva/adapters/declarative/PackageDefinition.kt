package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionSemantics
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
)

enum class PackageEffect { READ, WRITE, HANDOFF, UNKNOWN }

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
)

data class ReceiptText(
    val success: String? = null,
    val handlerMissing: String? = null,
)

sealed interface ScalarSlot {
    val type: String

    data class Argument(
        val name: String,
        override val type: String,
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
    data class Intent(
        val action: String,
        val uriBase: String,
        val query: Map<String, ScalarSlot>,
        val extras: Map<String, ScalarSlot>,
        val targetPackage: String?,
        val mimeType: String? = null,
        val packageByName: String? = null,
    ) : DeclarativeBinding

    data class Content(
        val uri: String,
        val authority: String,
        val projection: Map<String, String>,
        val selection: List<Predicate>,
        val maxRows: Int,
        val maxBytes: Int,
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
)

data class Evidence(
    val pointer: String,
    val expected: JsonElement,
)

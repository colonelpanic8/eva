package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

data class IntentRequest(
    val action: String,
    val uri: String,
    val extras: Map<String, JsonPrimitive>,
    val targetPackage: String?,
)

data class ContentRequest(
    val uri: String,
    val projection: Map<String, String>,
    val selection: String?,
    val selectionArguments: List<String>,
    val maxRows: Int,
    val maxBytes: Int,
)

data class HttpRequest(
    val origin: String,
    val url: String,
    val method: String,
    val body: String?,
    val credential: String?,
    val maxResponseBytes: Int,
)

class BindingArguments(
    capability: PackageCapability,
    arguments: Map<String, String>,
) {
    private val values =
        ExtensionProtocol.arguments(
            capability.inputSchema,
            ExtensionProtocol.encodeArguments(capability.inputSchema, arguments),
        )

    private fun value(slot: ScalarSlot): JsonPrimitive? =
        when (slot) {
            is ScalarSlot.Argument -> values[slot.name] as? JsonPrimitive
            is ScalarSlot.Literal -> slot.value
        }

    fun intent(binding: DeclarativeBinding.Intent): IntentRequest {
        val query = binding.query.mapNotNull { (name, slot) -> value(slot)?.let { encode(name) + "=" + encode(it.content) } }
        val uri = binding.uriBase + if (query.isEmpty()) "" else query.joinToString("&", "?")
        val extras = binding.extras.mapNotNull { (name, slot) -> value(slot)?.let { name to it } }.toMap()
        require(uri.toByteArray(Charsets.UTF_8).size <= ExtensionProtocol.ARGUMENT_BYTES)
        return IntentRequest(binding.action, uri, extras, binding.targetPackage)
    }

    fun content(binding: DeclarativeBinding.Content): ContentRequest {
        val arguments = binding.selection.map { requireNotNull(value(it.slot)) { "A selection argument is missing" }.content }
        return ContentRequest(
            binding.uri,
            binding.projection,
            binding.selection.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "${it.column} ${it.operator} ?" },
            arguments,
            binding.maxRows,
            binding.maxBytes,
        )
    }

    fun http(binding: DeclarativeBinding.Http): HttpRequest {
        var path = binding.path
        binding.parameters.filter { it.location == "path" }.forEach { parameter ->
            val text = requireNotNull(value(parameter.slot)) { "A path argument is missing" }.content
            require(text !in setOf(".", "..") && text.isNotEmpty())
            path = path.replace("{${parameter.name}}", encode(text))
        }
        val query =
            binding.parameters.filter { it.location == "query" }.mapNotNull { parameter ->
                value(parameter.slot)?.let { encode(parameter.name) + "=" + encode(it.content) }
            }
        val url = binding.origin + path + if (query.isEmpty()) "" else query.joinToString("&", "?")
        val body = binding.requestBody?.let { body(it).toString() }
        require(url.toByteArray(Charsets.UTF_8).size <= ExtensionProtocol.ARGUMENT_BYTES)
        require(body == null || body.toByteArray(Charsets.UTF_8).size <= ExtensionProtocol.ARGUMENT_BYTES)
        return HttpRequest(binding.origin, url, binding.method, body, binding.credential, binding.maxResponseBytes)
    }

    private fun body(mapping: BodyValue): JsonElement? =
        when (mapping) {
            is BodyValue.Scalar -> value(mapping.slot)
            is BodyValue.Fields -> JsonObject(mapping.fields.mapNotNull { (name, child) -> body(child)?.let { name to it } }.toMap())
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A")
}

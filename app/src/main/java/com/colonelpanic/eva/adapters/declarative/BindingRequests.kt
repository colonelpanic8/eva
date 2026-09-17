package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder

data class IntentRequest(
    val action: String,
    val uri: String,
    val extras: Map<String, JsonPrimitive>,
    val targetPackage: String?,
    val mimeType: String? = null,
    val appName: String? = null,
    val receipts: ReceiptText = ReceiptText(),
    val targetClass: String? = null,
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
    private val capability: PackageCapability,
    arguments: Map<String, String>,
) {
    private val values =
        ExtensionProtocol.arguments(
            capability.inputSchema,
            ExtensionProtocol.encodeArguments(capability.inputSchema, arguments),
        )

    init {
        capability.validators.forEach { (argument, validator) ->
            values[argument]?.let {
                require(
                    NamedValidators.accepts(validator, (it as JsonPrimitive).content),
                ) { "Named validation failed" }
            }
        }
    }

    private fun value(slot: ScalarSlot): JsonElement? =
        when (slot) {
            is ScalarSlot.Argument -> {
                val value = values[slot.name] ?: slot.default
                require(value != null || !slot.required) { "A required binding argument is missing" }
                val mapping = slot.values
                if (value != null && mapping != null) JsonPrimitive(mapping.getValue((value as JsonPrimitive).content)) else value
            }

            is ScalarSlot.Literal -> {
                slot.value
            }
        }

    /** Every slot outside a JSON body is scalar by construction; the codec rejects array slots there. */
    private fun scalar(slot: ScalarSlot): JsonPrimitive? = value(slot)?.let { it as? JsonPrimitive ?: error("Expected a scalar slot") }

    fun intent(binding: DeclarativeBinding.Intent): IntentRequest {
        val action =
            binding.action
                ?: requireNotNull(scalar(requireNotNull(binding.actionSlot))) { "An action argument is missing" }.content.also {
                    require(PackageCodec.isIntentAction(it)) { "Mapped action is not an intent action" }
                }
        val fixed = binding.query.mapNotNull { (name, slot) -> scalar(slot)?.let { encode(name) + "=" + encode(it.content) } }
        val spread =
            binding.querySpread?.let { name -> values[name] as? JsonObject }.orEmpty().map { (key, value) ->
                require(binding.query.keys.none { it.equals(key, ignoreCase = true) }) { "A spread parameter cannot replace a fixed one" }
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Spread values are strings")
                encode(key) + "=" + encode(text)
            }
        val query = fixed + spread
        var base = binding.uriBase
        binding.path.forEach { (name, slot) ->
            val text = requireNotNull(scalar(slot)) { "A URI argument is missing" }.content
            require(text.isNotEmpty()) { "A URI argument is empty" }
            base = base.replace("{$name}", encode(text))
        }
        binding.uriArgument?.let { name ->
            val value = requireNotNull(values[name] as? JsonPrimitive) { "A URI argument is missing" }.content
            require(value.length <= 2048 && value.none { it.isISOControl() || it.isWhitespace() }) { "Invalid URI" }
            val parsed = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid URI") }
            require(parsed.isAbsolute && parsed.scheme.lowercase() in binding.uriSchemes) { "URI scheme is not allowed by this package" }
            base = value
        }
        val uri =
            base +
                (
                    binding.opaque?.let { encode(requireNotNull(scalar(it)) { "An opaque URI argument is missing" }.content) }
                        ?: if (query.isEmpty()) "" else query.joinToString("&", "?")
                )
        val extras = binding.extras.mapNotNull { (name, slot) -> scalar(slot)?.let { name to it } }.toMap()
        require(uri.toByteArray(Charsets.UTF_8).size <= ExtensionProtocol.ARGUMENT_BYTES)
        val appName =
            binding.packageByName?.let { name ->
                requireNotNull(values[name] as? JsonPrimitive) { "Name the target app" }.content.also {
                    require(it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl))
                }
            }
        return IntentRequest(
            action,
            uri,
            extras,
            binding.targetPackage,
            binding.mimeType,
            appName,
            capability.receipts,
            binding.targetClass,
        )
    }

    fun select(binding: DeclarativeBinding): DeclarativeBinding =
        if (binding is DeclarativeBinding.Select) {
            if (binding.argument in values) binding.present else binding.absent
        } else {
            binding
        }

    fun content(binding: DeclarativeBinding.Content): ContentRequest {
        val arguments = binding.selection.map { requireNotNull(scalar(it.slot)) { "A selection argument is missing" }.content }
        var base = binding.uri
        binding.path.forEach { (name, slot) ->
            val text = requireNotNull(scalar(slot)) { "A path argument is missing" }.content
            require(text.isNotEmpty() && text !in setOf(".", "..") && text.none { it == '/' || it == '\\' })
            base = base.replace("{$name}", encode(text))
        }
        val query = binding.query.mapNotNull { (name, slot) -> scalar(slot)?.let { encode(name) + "=" + encode(it.content) } }
        val uri = base + if (query.isEmpty()) "" else query.joinToString("&", "?")
        require(uri.toByteArray(Charsets.UTF_8).size <= ExtensionProtocol.ARGUMENT_BYTES)
        return ContentRequest(
            uri,
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
            val text = requireNotNull(scalar(parameter.slot)) { "A path argument is missing" }.content
            require(text !in setOf(".", "..") && text.isNotEmpty())
            path = path.replace("{${parameter.name}}", encode(text))
        }
        val query =
            binding.parameters.filter { it.location == "query" }.mapNotNull { parameter ->
                scalar(parameter.slot)?.let { encode(parameter.name) + "=" + encode(it.content) }
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

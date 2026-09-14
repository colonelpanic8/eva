package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionMode
import com.colonelpanic.eva.capability.ExecutionSemantics
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/** Decoding validates authority and templates; it performs no I/O or authorization. */
object PackageCodec {
    const val MAX_BYTES = 262_144
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val packageId = Regex("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+")
    private val version = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
    private val pathSlot = Regex("\\{([A-Za-z_][A-Za-z0-9_]{0,63})}")
    private val scalarTypes = setOf("string", "integer", "number", "boolean")

    fun decode(json: String): PackageDefinition {
        val root = (BoundedJson.freeze(BoundedJson.parse(json, MAX_BYTES)) as? JsonObject) ?: error("Expected package object")
        root.fields(setOf("formatVersion", "id", "version", "title", "capabilities"))
        require(root.getValue("formatVersion").long() == 1L)
        val id = root.text("id", 128).also { require(packageId.matches(it)) }
        val revision = root.text("version", 40).also { require(version.matches(it)) }
        val capabilities =
            root
                .getValue("capabilities")
                .array()
                .also { require(it.size in 1..64) }
                .map { capability(it.obj()) }
        require(capabilities.map { it.name }.distinct().size == capabilities.size) { "Duplicate tool name" }
        return PackageDefinition(id, revision, root.text("title", 120), capabilities, BoundedJson.digest(root), root)
    }

    private fun capability(root: JsonObject): PackageCapability {
        root.fields(setOf("tool", "title", "binding", "execution"), setOf("effects"))
        val tool = root.getValue("tool").obj()
        tool.fields(setOf("name", "description", "inputSchema"))
        val name = tool.text("name", 64).also { require(identifier.matches(it)) }
        val schema = tool.getValue("inputSchema").obj().also(ExtensionProtocol::checkSchema)
        val properties = schema.getValue("properties").obj()
        val binding = binding(root.getValue("binding").obj(), properties)
        val claimed =
            when (root["effects"]?.string()) {
                "read" -> PackageEffect.READ
                "write" -> PackageEffect.WRITE
                "external_handoff" -> PackageEffect.HANDOFF
                null, "unknown" -> PackageEffect.UNKNOWN
                else -> error("Unsupported effects")
            }
        val effect =
            when {
                claimed == PackageEffect.UNKNOWN -> claimed
                binding is DeclarativeBinding.Intent -> if (claimed == PackageEffect.WRITE) claimed else PackageEffect.HANDOFF
                binding is DeclarativeBinding.Http && binding.method !in setOf("GET", "HEAD") -> PackageEffect.WRITE
                else -> claimed
            }
        val execution = execution(root.getValue("execution").obj())
        require((execution.mode == ExecutionMode.HANDOFF) == (binding is DeclarativeBinding.Intent))
        require(binding !is DeclarativeBinding.Intent || execution.requiresForeground)
        return PackageCapability(name, root.text("title", 120), tool.text("description", 2000), schema, effect, execution, binding)
    }

    private fun execution(root: JsonObject): ExecutionSemantics {
        root.fields(setOf("mode", "requiresForeground", "cancellation", "idempotency", "reconciliation"), setOf("maxWaitMillis"))
        val mode =
            when (root.text("mode", 30)) {
                "synchronous" -> ExecutionMode.SYNCHRONOUS
                "handoff" -> ExecutionMode.HANDOFF
                else -> error("Unsupported execution mode")
            }
        val foreground =
            (root["requiresForeground"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Expected boolean")
        return ExecutionSemantics(
            mode,
            foreground,
            root["maxWaitMillis"]?.takeUnless { it == JsonNull }?.long(),
            root.text("cancellation", 20),
            root.text("idempotency", 20),
            root.text("reconciliation", 20),
        )
    }

    private fun binding(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding =
        when (root.text("kind", 30)) {
            "android.intent" -> intent(root, properties)
            "android.content" -> content(root, properties)
            "http" -> http(root, properties)
            else -> error("Unsupported binding")
        }

    private fun intent(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Intent {
        root.fields(setOf("kind", "action", "uri"), setOf("extras", "package"))
        val action = root.text("action", 200).also { require(Regex("[A-Za-z][A-Za-z0-9_.]+").matches(it)) }
        val uri = root.getValue("uri").obj()
        uri.fields(setOf("base"), setOf("query"))
        val base = uri.text("base", 2000)
        val parsed = URI(base)
        require(parsed.isAbsolute && parsed.scheme.lowercase() !in setOf("intent", "file", "content", "javascript", "data"))
        require(parsed.rawFragment == null && parsed.rawUserInfo == null && '?' !in base)
        val query = slots(uri["query"], properties)
        val extras = slots(root["extras"], properties)
        val target = root["package"]?.string()?.also { require(packageId.matches(it)) }
        return DeclarativeBinding.Intent(action, base, query, extras, target)
    }

    private fun content(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Content {
        root.fields(setOf("kind", "authority", "uri", "projection", "maxRows", "maxBytes"), setOf("selection"))
        val authority = root.text("authority", 200).also { require(packageId.matches(it)) }
        val uri = root.text("uri", 2000)
        val parsed = URI(uri)
        require(parsed.scheme == "content" && parsed.rawAuthority == authority && parsed.rawFragment == null && parsed.rawQuery == null)
        val projection =
            root.getValue("projection").obj().also { require(it.size in 1..32) }.mapValues { (name, type) ->
                require(identifier.matches(name))
                type.string().also { require(it in scalarTypes) }
            }
        val selection =
            root["selection"]?.array().orEmpty().also { require(it.size <= 16) }.map {
                val condition = it.obj()
                condition.fields(setOf("column", "operator", "value"))
                val column = condition.text("column", 64).also { require(it in projection) }
                val operator = condition.text("operator", 8).also { require(it in setOf("=", "!=", "<", "<=", ">", ">=", "LIKE")) }
                val value = slot(condition.getValue("value").obj(), properties)
                require(value.type == projection[column])
                require(operator != "LIKE" || value.type == "string")
                Predicate(column, operator, value)
            }
        return DeclarativeBinding.Content(
            uri,
            authority,
            projection,
            selection,
            root.bounded("maxRows", 100),
            root.bounded("maxBytes", 16_384),
        )
    }

    private fun http(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Http {
        root.fields(
            setOf("kind", "origin", "method", "path", "parameters", "maxResponseBytes", "result"),
            setOf("requestBody", "credential"),
        )
        val origin = root.text("origin", 2000)
        val parsed = URI(origin)
        require(parsed.scheme == "https" && parsed.host != null && parsed.rawUserInfo == null && parsed.rawPath.isEmpty())
        require(parsed.rawQuery == null && parsed.rawFragment == null && (parsed.port == -1 || parsed.port in 1..65535))
        val method = root.text("method", 10).also { require(it in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) }
        val path = root.text("path", 2000)
        require(path.startsWith('/') && !path.startsWith("//") && path.none { it in "?#\\%" || it.isWhitespace() || it.isISOControl() })
        require(path.split('/').none { it == "." || it == ".." })
        val placeholders = pathSlot.findAll(path).map { it.groupValues[1] }.toList()
        require(pathSlot.replace(path, "").none { it == '{' || it == '}' })
        val parameters =
            root.getValue("parameters").array().also { require(it.size <= 64) }.map {
                val parameter = it.obj()
                parameter.fields(setOf("in", "name", "value"))
                val location = parameter.text("in", 10).also { require(it in setOf("path", "query")) }
                val name = parameter.text("name", 64).also { require(identifier.matches(it)) }
                Parameter(location, name, slot(parameter.getValue("value").obj(), properties))
            }
        require(parameters.map { it.location to it.name }.distinct().size == parameters.size)
        require(parameters.filter { it.location == "path" }.map { it.name }.toSet() == placeholders.toSet())
        val body = root["requestBody"]?.let { body(it.obj(), properties) as? BodyValue.Fields ?: error("Body must be an object") }
        require(body == null || method !in setOf("GET", "HEAD"))
        val credential = root["credential"]?.string()?.also { require(Regex("[a-z][a-z0-9_-]{0,63}").matches(it)) }
        val result = root.getValue("result").obj()
        result.fields(setOf("pointer", "maxBytes"), setOf("evidence"))
        val evidence =
            result["evidence"]?.obj()?.let {
                it.fields(setOf("pointer", "equals"))
                val expected = it.getValue("equals")
                require(expected is JsonPrimitive && expected != JsonNull)
                Evidence(pointer(it), expected)
            }
        return DeclarativeBinding.Http(
            origin,
            method,
            path,
            parameters,
            body,
            credential,
            root.bounded("maxResponseBytes", 1_048_576),
            ResultProjection(pointer(result), result.bounded("maxBytes", 16_384), evidence),
        )
    }

    private fun pointer(root: JsonObject): String =
        root.getValue("pointer").string().also {
            require(it.length <= 1000 && (it.isEmpty() || it.startsWith('/')) && !Regex("~(?![01])").containsMatchIn(it))
        }

    private fun body(
        root: JsonObject,
        properties: JsonObject,
    ): BodyValue =
        if (root.keys == setOf("fields")) {
            BodyValue.Fields(
                root.getValue("fields").obj().also { require(it.size <= 64) }.mapValues { (key, value) ->
                    require(identifier.matches(key))
                    body(value.obj(), properties)
                },
            )
        } else {
            BodyValue.Scalar(slot(root, properties))
        }

    private fun slots(
        value: JsonElement?,
        properties: JsonObject,
    ): Map<String, ScalarSlot> =
        value
            ?.obj()
            ?.also { require(it.size <= 64) }
            ?.mapValues { (key, child) ->
                require(key.length in 1..200 && key.none(Char::isISOControl))
                slot(child.obj(), properties)
            }.orEmpty()

    private fun slot(
        root: JsonObject,
        properties: JsonObject,
    ): ScalarSlot {
        val type = root.text("type", 10).also { require(it in scalarTypes) }
        return if ("argument" in root) {
            root.fields(setOf("argument", "type"))
            val name = root.text("argument", 64)
            require(properties[name]?.obj()?.get("type") == JsonPrimitive(type)) { "Slot type does not match tool schema" }
            ScalarSlot.Argument(name, type)
        } else {
            root.fields(setOf("value", "type"))
            val value = root.getValue("value") as? JsonPrimitive ?: error("Expected scalar literal")
            val schema = JsonObject(mapOf("type" to JsonPrimitive(type)))
            require(ToolSchema.error(schema, value) == null) { "Literal does not match type" }
            require(value != JsonNull)
            ScalarSlot.Literal(value, type)
        }
    }

    private fun JsonObject.fields(
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "Unexpected or missing fields" }
    }

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("Expected object")

    private fun JsonElement.array(): JsonArray = this as? JsonArray ?: error("Expected array")

    private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")

    private fun JsonElement.long(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Expected integer")

    private fun JsonObject.text(
        key: String,
        max: Int,
    ): String = getValue(key).string().also { require(it.length in 1..max) }

    private fun JsonObject.bounded(
        key: String,
        max: Int,
    ): Int = getValue(key).long().also { require(it in 1..max.toLong()) }.toInt()
}

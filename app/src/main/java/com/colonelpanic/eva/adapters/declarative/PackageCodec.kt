package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CallEnding
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

    /** Guidance goes into every session that offers the package, so it stays short. */
    const val GUIDANCE_CHARS = 1_500
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val packageId = Regex("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+")
    private val version = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
    private val pathSlot = Regex("\\{([A-Za-z_][A-Za-z0-9_]{0,63})\\}")
    private val scalarTypes = setOf("string", "integer", "number", "boolean")
    private val intentAction = Regex("[A-Za-z][A-Za-z0-9_.]+")
    private val FORBIDDEN_SCHEMES = setOf("intent", "file", "javascript", "data")

    fun isIntentAction(value: String): Boolean = value.length <= 200 && intentAction.matches(value)

    fun decode(json: String): PackageDefinition {
        val root = (BoundedJson.freeze(BoundedJson.parse(json, MAX_BYTES)) as? JsonObject) ?: error("Expected package object")
        root.fields(
            setOf("formatVersion", "id", "version", "title", "capabilities"),
            setOf("androidPackages", "description", "setup", "guidance"),
        )
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
        val apps =
            root["androidPackages"]?.array().orEmpty().also { require(it.size <= 16) }.map {
                it.string().also { name -> require(name.length <= 200 && packageId.matches(name)) }
            }
        require(apps.distinct().size == apps.size)
        val description = root["description"]?.let { root.text("description", 2000) }
        val guidance = root["guidance"]?.let { root.text("guidance", GUIDANCE_CHARS) }
        val setup =
            root["setup"]?.array()?.also { require(it.size in 1..8) }.orEmpty().map {
                it.string().also { step -> require(step.length in 1..300 && step.none(Char::isISOControl)) }
            }
        return PackageDefinition(
            id,
            revision,
            root.text("title", 120),
            capabilities,
            BoundedJson.digest(root),
            root,
            apps,
            description,
            setup,
            guidance,
        ).also { definition ->
            require(
                definition.httpBindings().filter { it.credential != null }.groupBy { it.origin }.values.all { bindings ->
                    bindings.map { it.credentialScheme }.distinct().size == 1
                },
            ) { "An HTTP origin must use one credential scheme." }
        }
    }

    private fun capability(root: JsonObject): PackageCapability {
        root.fields(setOf("tool", "binding", "execution"), setOf("effects", "validators", "receipts", "_meta"))
        root["_meta"]?.obj()
        val tool = ExtensionProtocol.tool(root.getValue("tool").obj())
        val name = tool.name
        val schema = tool.inputSchema
        val properties = schema.getValue("properties").obj()
        val validators =
            root["validators"]
                ?.obj()
                ?.mapValues { (argument, validator) ->
                    require(properties[argument]?.obj()?.get("type") == JsonPrimitive("string"))
                    validator.string().also { require(it in NamedValidators.names) { "Unsupported named validator" } }
                }.orEmpty()
        val receipts =
            root["receipts"]?.obj()?.let {
                it.fields(emptySet(), setOf("success", "handlerMissing"))
                ReceiptText(
                    it["success"]?.string()?.also { text -> require(text.length in 1..1000) },
                    it["handlerMissing"]?.string()?.also { text -> require(text.length in 1..1000) },
                )
            } ?: ReceiptText()
        val binding = binding(root.getValue("binding").obj(), properties)
        val alternatives = if (binding is DeclarativeBinding.Select) listOf(binding.present, binding.absent) else listOf(binding)
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
                claimed == PackageEffect.UNKNOWN -> {
                    claimed
                }

                alternatives.any { it is DeclarativeBinding.Http && it.method !in setOf("GET", "HEAD") } -> {
                    PackageEffect.WRITE
                }

                alternatives.any { it is DeclarativeBinding.Intent } -> {
                    if (claimed ==
                        PackageEffect.WRITE
                    ) {
                        claimed
                    } else {
                        PackageEffect.HANDOFF
                    }
                }

                else -> {
                    claimed
                }
            }
        tool.annotations?.let { ExtensionProtocol.checkAnnotations(it, effect.toEffect()) }
        val execution = execution(root.getValue("execution").obj())
        require(alternatives.all { (execution.mode == ExecutionMode.HANDOFF) == (it is DeclarativeBinding.Intent) })
        require(alternatives.none { it is DeclarativeBinding.Intent } || execution.requiresForeground)
        require(
            !execution.requiresUnlock || alternatives.all { it is DeclarativeBinding.Intent },
        ) { "Only intent bindings can require unlock" }
        return PackageCapability(
            name,
            tool.title,
            tool.description,
            schema,
            effect,
            execution,
            binding,
            validators,
            receipts,
            tool.outputSchema,
            tool.annotations,
        )
    }

    private fun execution(root: JsonObject): ExecutionSemantics {
        root.fields(setOf("mode", "requiresForeground"), setOf("maxWaitMillis", "requiresUnlock", "endsVoiceCall"))
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
            root["requiresUnlock"]?.let {
                (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
                    ?: error("Expected boolean")
            }
                ?: false,
            root["endsVoiceCall"]?.let { ExtensionProtocol.callEnding(it) } ?: CallEnding.NEVER,
        )
    }

    private fun binding(
        root: JsonObject,
        properties: JsonObject,
        allowSelect: Boolean = true,
    ): DeclarativeBinding =
        when (root.text("kind", 30)) {
            "select" -> {
                require(allowSelect)
                root.fields(setOf("kind", "argument", "present", "absent"))
                val argument = root.text("argument", 64).also { require(it in properties) }
                DeclarativeBinding.Select(
                    argument,
                    binding(root.getValue("present").obj(), properties, false),
                    binding(root.getValue("absent").obj(), properties, false),
                )
            }

            "android.intent" -> {
                intent(root, properties)
            }

            "android.content" -> {
                content(root, properties)
            }

            "http" -> {
                http(root, properties)
            }

            else -> {
                error("Unsupported binding")
            }
        }

    private fun intent(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Intent {
        root.fields(setOf("kind", "action"), setOf("uri", "extras", "package", "mimeType", "packageByName", "class", "querySpread"))
        val actionSlot =
            (root.getValue("action") as? JsonObject)?.let { spec ->
                val argument = slot(spec, properties) as? ScalarSlot.Argument ?: error("An action slot names an argument")
                val mapped = requireNotNull(argument.values) { "An action slot needs a closed value map" }
                require(mapped.values.all(::isIntentAction)) { "Every mapped action must be an intent action" }
                argument
            }
        val action = if (actionSlot == null) root.text("action", 200).also { require(isIntentAction(it)) } else null
        val uri = root["uri"]?.obj()
        val wholeUri = uri != null && "argument" in uri
        val uriArgument =
            if (wholeUri) {
                uri.fields(setOf("argument", "schemes"))
                uri.text("argument", 64).also { require(properties[it]?.obj()?.get("type") == JsonPrimitive("string")) }
            } else {
                null
            }
        val uriSchemes =
            if (wholeUri) {
                uri
                    .getValue("schemes")
                    .array()
                    .also { require(it.size in 1..8) }
                    .map { scheme ->
                        scheme.string().also {
                            require(Regex("[a-z][a-z0-9+.-]*").matches(it) && it !in FORBIDDEN_SCHEMES) { "Unsupported URI scheme" }
                        }
                    }.also { require(it.distinct().size == it.size) }
            } else {
                emptyList()
            }
        val path = if (wholeUri) emptyMap() else slots(uri?.get("path"), properties)
        val base =
            uri
                ?.takeUnless { wholeUri }
                ?.let {
                    it.fields(setOf("base"), setOf("query", "opaque", "path"))
                    it.text("base", 2000).also { base ->
                        val placeholders = pathSlot.findAll(base).map { match -> match.groupValues[1] }.toSet()
                        require(
                            path.keys == placeholders && path.keys.all(identifier::matches),
                        ) { "URI placeholders and path slots must match" }
                        val fixed = pathSlot.replace(base, "placeholder")
                        val parsed = URI(if ("opaque" in it) fixed + "placeholder" else fixed)
                        require(parsed.isAbsolute && parsed.scheme.lowercase() !in FORBIDDEN_SCHEMES)
                        // A provider URI is only a fixed destination; no slot may shape it.
                        require(parsed.scheme.lowercase() != "content" || it.keys == setOf("base")) {
                            "A content URI must be fixed"
                        }
                        require(parsed.rawFragment == null && parsed.rawUserInfo == null && '?' !in base)
                        require(pathSlot.findAll(base).all { match -> match.range.first > base.indexOf(':') }) {
                            "A placeholder cannot form the scheme"
                        }
                    }
                }.orEmpty()
        val opaque =
            uri?.takeUnless { wholeUri }?.get("opaque")?.let { value ->
                require(Regex("[A-Za-z][A-Za-z0-9+.-]*:").matches(base)) { "An opaque slot needs a fixed scheme-only base" }
                require("query" !in uri) { "Opaque slots cannot be combined with query mappings" }
                slot(value.obj(), properties).also { require(it.type == "string") }
            }
        val query = if (wholeUri) emptyMap() else slots(uri?.get("query"), properties)
        val querySpread =
            root["querySpread"]?.obj()?.let { spread ->
                spread.fields(setOf("argument"))
                val argument = spread.text("argument", 64)
                val property = properties[argument]?.obj() ?: error("querySpread names a tool argument")
                require(property["type"] == JsonPrimitive("object") && property["additionalProperties"] is JsonObject) {
                    "querySpread needs a string-map argument"
                }
                require(uri != null && !wholeUri && "opaque" !in uri) { "querySpread needs a fixed base with query parameters" }
                argument
            }
        val extras = slots(root["extras"], properties)
        val target = root["package"]?.string()?.also { require(packageId.matches(it)) }
        val targetClass =
            root["class"]?.let {
                require(target != null) { "A fixed class requires a fixed package" }
                root.text("class", 300).also { name ->
                    require(
                        Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matches(name),
                    ) { "Use a fully qualified fixed activity class" }
                }
            }
        val mimeType = root["mimeType"]?.string()?.also { require(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it)) }
        val byName =
            root["packageByName"]?.string()?.also {
                require(properties[it]?.obj()?.get("type") == JsonPrimitive("string"))
                require(target == null) { "Choose either a fixed package or a visible app name" }
            }
        return DeclarativeBinding.Intent(
            action,
            base,
            query,
            extras,
            target,
            mimeType,
            byName,
            opaque,
            targetClass,
            path,
            actionSlot,
            uriArgument,
            uriSchemes,
            querySpread,
        )
    }

    private fun content(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Content {
        root.fields(setOf("kind", "authority", "uri", "projection", "maxRows", "maxBytes"), setOf("selection"))
        val authority = root.text("authority", 200).also { require(packageId.matches(it)) }
        val uriObject = root["uri"] as? JsonObject
        uriObject?.fields(setOf("base"), setOf("query", "path"))
        val uri = uriObject?.text("base", 2000) ?: root.text("uri", 2000)
        val path = slots(uriObject?.get("path"), properties)
        val placeholders = pathSlot.findAll(uri).map { it.groupValues[1] }.toSet()
        require(path.keys == placeholders && path.keys.all(identifier::matches))
        val parsed = URI(pathSlot.replace(uri, "placeholder"))
        require(parsed.scheme == "content" && parsed.rawAuthority == authority && parsed.rawFragment == null && parsed.rawQuery == null)
        if (uriObject != null) {
            require(parsed.rawPath.startsWith('/') && !parsed.rawPath.startsWith("//"))
            require(parsed.rawPath.none { it in "%\\" || it.isWhitespace() || it.isISOControl() })
            require(parsed.rawPath.split('/').none { it == "." || it == ".." })
        }
        require(pathSlot.findAll(uri).all { match -> match.range.first > uri.indexOf('/', "content://".length) })
        val query = slots(uriObject?.get("query"), properties)
        val projection =
            root.getValue("projection").obj().also { require(it.size in 1..32) }.mapValues { (name, type) ->
                require(identifier.matches(name))
                type.string().also { require(it in scalarTypes || it == "json") }
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
            query,
            path,
        )
    }

    private fun http(
        root: JsonObject,
        properties: JsonObject,
    ): DeclarativeBinding.Http {
        root.fields(
            setOf("kind", "origin", "method", "path", "parameters", "maxResponseBytes", "result"),
            setOf("requestBody", "credential", "credentialScheme"),
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
        val scheme = root["credentialScheme"]?.string() ?: "basic"
        require(scheme in setOf("basic", "bearer"))
        require("credentialScheme" !in root || credential != null)
        val result = root.getValue("result").obj()
        result.fields(setOf("maxBytes"), setOf("pointer", "items", "evidence", "notExecutedStatuses"))
        require(("pointer" in result) != ("items" in result))
        val items = result["items"]?.obj()?.let { items(it, properties) }
        val rejectedStatuses =
            result["notExecutedStatuses"]
                ?.array()
                ?.map {
                    it
                        .long()
                        .also { code ->
                            require(code in 400..499)
                        }.toInt()
                }?.toSet()
                .orEmpty()
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
            ResultProjection(
                if (items ==
                    null
                ) {
                    pointer(result)
                } else {
                    ""
                },
                result.bounded("maxBytes", 16_384),
                evidence,
                items,
                rejectedStatuses,
            ),
            credentialScheme = scheme,
        )
    }

    private fun items(
        root: JsonObject,
        properties: JsonObject,
    ): ItemProjection {
        root.fields(setOf("arrayPaths", "line", "fields", "maxItems", "truncationNote"), setOf("totalPointer", "filter"))
        val paths =
            root.getValue("arrayPaths").array().also { require(it.size in 1..4) }.map {
                pointer(JsonObject(mapOf("pointer" to it))).also { path ->
                    require(path.split('/').count { segment -> segment == "*" } <= 1)
                }
            }
        val line = root.text("line", 2000).also { require(it.none(Char::isISOControl)) }
        val fields =
            root.getValue("fields").obj().also { require(it.size in 1..32) }.mapValues { (name, field) ->
                require(identifier.matches(name))
                val value = field.obj()
                value.fields(setOf("pointer", "type"), setOf("required"))
                val type = value.text("type", 20).also { require(it in scalarTypes || it == "stringArray") }
                ItemField(pointer(value), type, value["required"]?.bool() ?: false)
            }
        require(pathSlot.findAll(line).map { it.groupValues[1] }.toSet() == fields.keys)
        require(pathSlot.replace(line, "").none { it == '{' || it == '}' })
        val note = root.text("truncationNote", 500).also { require(it.none(Char::isISOControl)) }
        val total = root["totalPointer"]?.let { pointer(JsonObject(mapOf("pointer" to it))) }
        val filter =
            root["filter"]?.obj()?.let { value ->
                value.fields(setOf("fields", "argument"))
                val argument = value.text("argument", 64)
                require(properties[argument]?.obj()?.get("type") == JsonPrimitive("string")) { "Filter argument must name a string input" }
                val pointers =
                    value.getValue("fields").array().also { require(it.size in 1..16) }.map {
                        pointer(JsonObject(mapOf("pointer" to it)))
                    }
                require(pointers.distinct().size == pointers.size)
                ItemFilter(pointers, argument)
            }
        return ItemProjection(paths, line, fields, root.bounded("maxItems", 100), note, total, filter)
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
            BodyValue.Scalar(slot(root, properties, allowArray = true))
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
        allowArray: Boolean = false,
    ): ScalarSlot {
        val type = root.text("type", 10).also { require(it in scalarTypes || (allowArray && it == "array")) }
        return if ("argument" in root) {
            root.fields(setOf("argument", "type"), setOf("default", "required", "values"))
            val name = root.text("argument", 64)
            require(properties[name]?.obj()?.get("type") == JsonPrimitive(type)) { "Slot type does not match tool schema" }
            val default =
                root["default"]?.let {
                    require(it != JsonNull && (it is JsonPrimitive || it is JsonArray))
                    require(
                        ToolSchema.error(properties.getValue(name).obj(), it) == null,
                    ) { "Default does not satisfy the argument schema" }
                    it
                }
            val values =
                root["values"]?.obj()?.let { mapping ->
                    require(type == "string") { "Value maps apply to string arguments" }
                    val allowed =
                        properties
                            .getValue(name)
                            .obj()["enum"]
                            ?.array()
                            ?.map { it.string() }
                            ?.toSet()
                    require(allowed != null && mapping.keys == allowed) { "A value map must cover the argument enum exactly" }
                    mapping.mapValues { (_, bound) ->
                        bound.string().also { require(it.length in 1..200 && it.none(Char::isISOControl)) { "Invalid mapped value" } }
                    }
                }
            ScalarSlot.Argument(name, type, default, root["required"]?.bool() ?: false, values)
        } else {
            require(type != "array") { "Literal slots are scalars" }
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

    private fun JsonElement.bool(): Boolean =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Expected boolean")

    private fun JsonObject.text(
        key: String,
        max: Int,
    ): String = getValue(key).string().also { require(it.length in 1..max) }

    private fun JsonObject.bounded(
        key: String,
        max: Int,
    ): Int = getValue(key).long().also { require(it in 1..max.toLong()) }.toInt()
}

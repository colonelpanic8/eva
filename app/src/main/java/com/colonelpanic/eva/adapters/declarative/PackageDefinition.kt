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
    /** What the package is for, shown with its actions before the user installs it. */
    val description: String? = null,
    /** What the user must do outside EVA before the actions work; claims about the package, never by it. */
    val setup: List<String> = emptyList(),
    /** How the package's tools fit together, for the model; external data like its tool descriptions. */
    val guidance: String? = null,
    /** User-set, non-secret values the bindings read through setting slots; secrets stay credential references. */
    val settings: Map<String, PackageSetting> = emptyMap(),
    /** Tools that serve EVA's shared messaging tools under a service name instead of being offered directly. */
    val messaging: MessagingRole? = null,
)

/**
 * One configurable value. [schema] is the restricted JSON Schema its value must satisfy; values live in
 * the user's configuration, never in the package, and are never secrets.
 */
data class PackageSetting(
    val name: String,
    val title: String,
    val description: String?,
    val schema: JsonObject,
    val default: JsonPrimitive?,
) {
    val type: String get() = (schema.getValue("type") as JsonPrimitive).content
}

/** A fixed text or the value of a setting. */
sealed interface TextSource {
    data class Fixed(
        val value: String,
    ) : TextSource

    data class Setting(
        val name: String,
    ) : TextSource
}

/**
 * Which package tools answer EVA's shared messaging tools. Argument fields name the package tool's own
 * inputs; [StartChat.conversation] points into the operation's final record for the new conversation ID.
 */
data class MessagingRole(
    val service: TextSource,
    val label: TextSource,
    val conversations: Lookup,
    val contacts: Lookup?,
    val history: History,
    val send: Send,
    val startChat: StartChat?,
) {
    data class Lookup(
        val tool: String,
        val query: String?,
        val limit: String?,
    )

    data class History(
        val tool: String,
        val conversation: String,
        val limit: String?,
    )

    data class Send(
        val tool: String,
        val conversation: String,
        val text: String,
    )

    data class StartChat(
        val tool: String,
        val recipients: String,
        val conversation: String,
    )

    val tools: Set<String> get() = setOfNotNull(conversations.tool, contacts?.tool, history.tool, send.tool, startChat?.tool)
}

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
        /** Bound value for each tool enum value; keys cover the enum exactly, so the model never sees the target's codes. */
        val values: Map<String, String>? = null,
    ) : ScalarSlot

    data class Literal(
        val value: JsonPrimitive,
        override val type: String,
    ) : ScalarSlot

    /** The user's configured value for a declared package setting. */
    data class Setting(
        val name: String,
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
        /** The fixed action, or null when [actionSlot] chooses one from its closed value map. */
        val action: String?,
        val uriBase: String,
        val query: Map<String, ScalarSlot>,
        val extras: Map<String, ScalarSlot>,
        val targetPackage: String?,
        val mimeType: String? = null,
        val packageByName: String? = null,
        val opaque: ScalarSlot? = null,
        val targetClass: String? = null,
        /** Slots for `{name}` placeholders in the fixed base, each percent-encoded whole. */
        val path: Map<String, ScalarSlot> = emptyMap(),
        val actionSlot: ScalarSlot.Argument? = null,
        /** A string argument supplying the whole data URI, accepted only with a scheme in [uriSchemes]. */
        val uriArgument: String? = null,
        val uriSchemes: List<String> = emptyList(),
        /** A string-map argument whose entries become further query parameters, never overriding a fixed one. */
        val querySpread: String? = null,
    ) : DeclarativeBinding {
        init {
            require((action == null) != (actionSlot == null)) { "An intent has either a fixed action or an action slot" }
            require((uriArgument == null) == uriSchemes.isEmpty())
        }
    }

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
        val credentialScheme: String = "basic",
        /** A durable server-side operation EVA keys, polls, and maps to an outcome, or null for one exchange. */
        val operation: DurableOperation? = null,
    ) : DeclarativeBinding
}

/**
 * EVA sends [header] with a key derived from the invocation, so a re-delivered invocation names the same
 * server operation. It then reads [statusPath] (with `{operation}` replaced by the key) on the same origin
 * until [state] maps to a final outcome. A state absent from [outcomes] is an unknown outcome.
 */
data class DurableOperation(
    val header: String,
    val statusPath: String,
    val state: String,
    val detail: String?,
    val outcomes: Map<String, OperationOutcome>,
)

enum class OperationOutcome { COMPLETED, NOT_EXECUTED, UNKNOWN, PENDING }

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

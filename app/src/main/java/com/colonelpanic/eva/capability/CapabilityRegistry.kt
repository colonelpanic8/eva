package com.colonelpanic.eva.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

class CapabilityRegistry(
    backends: Map<String, ExecutionBackend>,
    definitions: List<CapabilityDefinition> = BundledCapabilities.definitions,
    bindingRevisions: Map<String, String> = backends.keys.associateWith { "native:10" },
) {
    private val admission = Mutex()

    @Volatile private var current = Snapshot.create(backends, definitions, bindingRevisions)
    val snapshot: Snapshot get() = current
    val catalog: List<CapabilityDefinition> get() = current.catalog

    /** Callers must change binding revisions when backend authority or semantics change. */
    suspend fun replace(
        backends: Map<String, ExecutionBackend>,
        definitions: List<CapabilityDefinition> = BundledCapabilities.definitions,
        bindingRevisions: Map<String, String> = backends.keys.associateWith { "native:10" },
    ) {
        val candidate = Snapshot.create(backends, definitions, bindingRevisions)
        admission.withLock { current = candidate }
    }

    fun resolve(proposal: ToolProposal): ExecutionBackend? = current.resolve(proposal)

    fun validationError(proposal: ToolProposal): String? = current.validationError(proposal)

    suspend fun changeAuthorization(update: suspend () -> Unit) = admission.withLock { update() }

    /** Only journal the dispatch transition here; never perform external work under this lock. */
    internal suspend fun commitDispatch(
        proposal: ToolProposal,
        onRejected: (String) -> Unit = {},
        commit: suspend () -> Unit,
    ): ExecutionBackend? =
        admission.withLock {
            val backend = current.resolve(proposal) ?: return@withLock null
            backend.dispatchRejection()?.let {
                onRejected(it)
                return@withLock null
            }
            commit()
            backend
        }

    class Snapshot private constructor(
        val catalog: List<CapabilityDefinition>,
        internal val bindings: Map<String, ExecutionBackend>,
        val bindingRevisions: Map<String, String>,
        val revision: String,
    ) {
        val definitions: Map<String, CapabilityDefinition> = Collections.unmodifiableMap(catalog.associateBy { it.id })

        fun resolve(proposal: ToolProposal): ExecutionBackend? =
            if (proposal.catalogRevision ==
                revision
            ) {
                bindings[proposal.capabilityId]
            } else {
                null
            }

        fun validationError(proposal: ToolProposal): String? {
            if (resolve(proposal) == null) return STALE_MESSAGE
            val definition = definitions.getValue(proposal.capabilityId)
            return ToolSchema.error(definition.inputSchema, ToolSchema.coerce(definition.inputSchema, proposal.arguments))
                ?: definition.validateOperation(proposal.arguments)
        }

        companion object {
            internal fun create(
                backends: Map<String, ExecutionBackend>,
                definitions: List<CapabilityDefinition>,
                bindingRevisions: Map<String, String>,
            ): Snapshot {
                val catalog =
                    definitions.filter { it.id in backends }.map {
                        it.copy(inputSchema = BoundedJson.freeze(it.inputSchema) as JsonObject)
                    }
                require(catalog.map { it.id }.distinct().size == catalog.size) { "Duplicate capability IDs" }
                require(backends.keys == catalog.map { it.id }.toSet()) { "Every backend needs a capability definition" }
                require(bindingRevisions.keys == backends.keys && bindingRevisions.values.all { it.isNotBlank() })
                catalog.forEach { ToolSchema.check(it.inputSchema) }
                val digest =
                    BoundedJson.digest(
                        JsonArray(
                            catalog.sortedBy { it.id }.map {
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(it.id),
                                        "title" to JsonPrimitive(it.title),
                                        "description" to JsonPrimitive(it.description),
                                        "schema" to it.inputSchema,
                                        "readOnly" to JsonPrimitive(it.readOnly),
                                        "source" to (it.source?.toJson() ?: JsonNull),
                                        "binding" to JsonPrimitive(bindingRevisions.getValue(it.id)),
                                    ),
                                )
                            },
                        ),
                    )
                return Snapshot(
                    Collections.unmodifiableList(catalog),
                    Collections.unmodifiableMap(backends.toMap()),
                    Collections.unmodifiableMap(bindingRevisions.toMap()),
                    digest,
                )
            }
        }
    }

    companion object {
        const val STALE_MESSAGE =
            "This action catalog changed or is unavailable. " +
                "Reconnect before requesting the action again. Nothing was executed."
        const val MAP_SEARCH = "eva.android.maps.search"
        const val NAVIGATE = "eva.android.maps.navigate"
        const val SMS_COMPOSE = "eva.android.messages.compose"
        const val SMS_SEND = "eva.android.messages.send"
        const val SET_ALARM = "eva.android.alarm.set"
        const val SET_TIMER = "eva.android.timer.set"
        const val DIAL = "eva.android.phone.dial"
        const val WEB_SEARCH = "eva.android.web.search"
        const val OPEN_URL = "eva.android.web.open"
        const val EMAIL_COMPOSE = "eva.android.email.compose"
        const val CALENDAR_EVENT = "eva.android.calendar.event"
        const val OPEN_APP = "eva.android.app.open"
        const val OPEN_SETTINGS = "eva.android.settings.open"
        const val CONTACTS_SEARCH = "eva.android.contacts.search"
        const val CONVERSATIONS_SEARCH = "eva.android.messages.conversations"
        const val CONVERSATION_READ = "eva.android.messages.history"
        const val DEVICE_STATE_GET = "eva.android.device.state.get"
        const val DEVICE_STATE_SET = "eva.android.device.state.set"
        const val DEVICE_STATE_METADATA = "eva.android.device.state.metadata"
        const val MEDIA_CONTROL = "eva.android.media.control"
        const val MEDIA_NOW_PLAYING = "eva.android.media.nowplaying"
        const val MEDIA_PLAY = "eva.android.media.play"
        const val MEDIA_QUEUE = "eva.android.media.queue"
        const val MEDIA_VOLUME = "eva.android.media.volume"
        const val UI_OBSERVE = "eva.device.observe"
        const val UI_TAP = "eva.device.tap"
        const val UI_SET_TEXT = "eva.device.set_text"

        /** Reading and driving another app's screen, which the user can withhold as a group. */
        val SCREEN_CONTROL = setOf(UI_OBSERVE, UI_TAP, UI_SET_TEXT)
        const val MAX_DESTINATION_LENGTH = 500
    }
}

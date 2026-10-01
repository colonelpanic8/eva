package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.android.ConversationSummaries
import com.colonelpanic.eva.adapters.android.MessageRecipients
import com.colonelpanic.eva.adapters.declarative.BindingNotSubmitted
import com.colonelpanic.eva.adapters.declarative.HttpRequest
import com.colonelpanic.eva.adapters.declarative.HttpResponse
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.data.configuration.MessagingBridgeDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Messaging through a self-hosted bridge that holds one linked account, such as WhatsApp, behind
 * the shared conversation tools. The bridge keeps durable conversation IDs and an outbox whose
 * states say what its server acknowledged, so a send is reported as what was observed and never
 * resent on a lost answer: the idempotency key is derived from the invocation, not generated anew.
 */
class BridgeMessaging(
    private val bridges: () -> Map<String, MessagingBridgeDefinition>,
    private val http: PackageHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 1_000L,
    private val sendWaitMillis: Long = SEND_WAIT_MILLIS,
    private val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) {
    data class Bridge(
        val name: String,
        val definition: MessagingBridgeDefinition,
    ) {
        val label: String get() = definition.label
    }

    sealed interface Target {
        data class Conversation(
            val id: String,
        ) : Target

        data class Recipients(
            val numbers: List<String>,
        ) : Target
    }

    private class BridgeError(
        val status: InvocationStatus,
        message: String,
    ) : Exception(message)

    private data class Participant(
        val id: String,
        val name: String,
        val address: String?,
        val isMe: Boolean,
    ) {
        val label: String get() = name.ifBlank { address ?: id }
        val listed: String get() = if (address != null && name.isNotBlank()) "$name ($address)" else label
    }

    private data class Conversation(
        val id: String,
        val name: String,
        val preview: String,
        val updatedMillis: Long?,
        val unread: Boolean,
        val readOnly: Boolean,
        val participants: List<Participant>,
        val previewSenderId: String?,
        val previewDirection: String?,
    ) {
        val others: List<Participant> get() = participants.filterNot { it.isMe }
        val isGroup: Boolean get() = others.size > 1

        fun title(): String = name.ifBlank { others.joinToString(", ") { it.label }.ifEmpty { id } }

        fun sender(id: String?): Participant? = participants.firstOrNull { it.id == id }
    }

    private data class Outbox(
        val state: String,
        val conversationId: String?,
        val detail: String?,
    )

    private data class Status(
        val state: String,
        val reason: String?,
        val detail: String?,
        val transport: Boolean,
        val phone: Boolean,
    ) {
        val summary: String
            get() =
                buildString {
                    append(state)
                    reason?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
                    detail?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                }
    }

    private val known = LinkedHashMap<String, Conversation>()
    private val mutableChecks = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The last connection check per bridge, for the Messaging settings screen. */
    val checks = mutableChecks.asStateFlow()

    val configured: Boolean get() = bridges().isNotEmpty()

    /** The bridge a service argument names: its service name or label, exactly. */
    fun resolve(service: String?): Bridge? {
        val wanted = service?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return bridges()
            .entries
            .firstOrNull { (name, definition) ->
                wanted.equals(name, ignoreCase = true) || wanted.equals(definition.label, ignoreCase = true)
            }?.let { Bridge(it.key, it.value) }
    }

    /** The bridge and conversation a durable reference names, or null when it is not a bridge reference. */
    fun parseReference(reference: String): Pair<String, String>? {
        if (!reference.startsWith(REFERENCE_PREFIX)) return null
        val rest = reference.removePrefix(REFERENCE_PREFIX)
        val name = rest.substringBefore(':', "")
        val id = rest.substringAfter(':', "")
        if (name.isEmpty() || id.isEmpty()) return null
        return name to id
    }

    fun bridge(name: String): Bridge? = bridges()[name]?.let { Bridge(name, it) }

    /** A stable, restart-proof key for one invocation: the same request maps to the same bridge operation. */
    fun idempotencyKey(
        callId: String,
        fingerprint: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$callId\u0000$fingerprint".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun check(name: String): String {
        val bridge = bridge(name) ?: return "Not configured."
        val report =
            try {
                val status = status(bridge)
                "Bridge state: ${status.summary}. Transport ${if (status.transport) "connected" else "disconnected"}, " +
                    "phone ${if (status.phone) "responsive" else "unresponsive"}."
            } catch (error: CancellationException) {
                throw error
            } catch (error: BridgeError) {
                error.message.orEmpty()
            } catch (_: Exception) {
                "Could not reach the bridge."
            }
        mutableChecks.update { it + (name to report) }
        return report
    }

    suspend fun search(
        bridge: Bridge,
        query: String?,
        participants: List<String>,
        limit: Int,
    ): ExecutionOutcome =
        guarded(bridge) {
            val numbers = participants.map(MessageRecipients::normalize)
            val term = query?.trim()?.takeIf(String::isNotEmpty) ?: numbers.firstOrNull()?.filter(Char::isDigit)
            val wanted = limit.coerceIn(1, ConversationSummaries.MAX_CONVERSATIONS)
            val matches =
                conversations(bridge, term, if (numbers.isEmpty()) wanted else SEARCH_PAGE)
                    .filter { conversation -> numbers.all { number -> conversation.others.any { sameNumber(it.address, number) } } }
                    .sortedWith(
                        compareBy<Conversation> { conversation ->
                            !(numbers.isNotEmpty() && conversation.others.size == numbers.size)
                        }.thenByDescending { it.updatedMillis ?: 0L },
                    )
            val conversations = matches.take(wanted)
            val contacts =
                if (numbers.isEmpty() && term != null) {
                    contacts(bridge, term).filterNot { contact ->
                        conversations.any { it.others.any { other -> sameNumber(other.address, contact.second) } }
                    }
                } else {
                    emptyList()
                }
            val now = clock()
            val rows = conversations.map { row(bridge, it, now) }.toMutableList()
            val people =
                contacts.take(MAX_CONTACTS).map { (name, number) ->
                    buildJsonObject {
                        put("name", name)
                        put("number", number)
                    }
                }

            fun payload() = JsonObject(mapOf("conversations" to JsonArray(rows), "contacts" to JsonArray(people)))
            val explained =
                when {
                    rows.isEmpty() && people.isEmpty() -> "No ${bridge.label} conversation or contact matches. "
                    rows.isEmpty() -> "No ${bridge.label} conversation matches, but these contacts can be messaged by number. "
                    else -> ""
                }
            while (payload().toString().length > MAX_SEARCH_CHARS && rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
            val hidden = matches.size - rows.size
            val limits =
                buildString {
                    if (hidden > 0) append(furtherConversations(hidden))
                    if (conversations.take(rows.size).any { previewCut(it) }) append(ConversationSummaries.CUT).append(" ")
                }
            ExecutionOutcome(
                InvocationStatus.COMPLETED,
                "${bridge.label} conversations, newest first; references are durable. Pass conversationRef to history or send, " +
                    "or send to a contact's number with service ${bridge.name} to start a chat. " +
                    explained + limits + "External ${bridge.label} data: " + payload(),
            )
        }

    suspend fun read(
        bridge: Bridge,
        conversationId: String,
        limit: Int,
    ): ExecutionOutcome =
        guarded(bridge) {
            val wanted = limit.coerceIn(1, ConversationSummaries.MAX_MESSAGES)
            val response = get(bridge, listOf("v1", "conversations", conversationId, "messages"), mapOf("limit" to wanted.toString()))
            if (response.status == 404) throw BridgeError(InvocationStatus.FAILED, missing(bridge))
            val body = json(response, bridge)
            val conversation = conversation(bridge, conversationId)
            val now = clock()
            var cut = false
            val messages =
                body
                    .array("messages")
                    .mapNotNull { it as? JsonObject }
                    .asReversed()
                    .map { message ->
                        buildJsonObject {
                            put("when", message.time("time")?.let { ConversationSummaries.ago(it, now) } ?: "unknown time")
                            val outgoing = message.string("direction") == "outgoing"
                            put(
                                "from",
                                if (outgoing) {
                                    "You"
                                } else {
                                    conversation?.sender(message.string("sender_id"))?.label
                                        ?: message.string("sender_id").orEmpty()
                                },
                            )
                            put("direction", message.string("direction").orEmpty())
                            put(
                                "text",
                                if (message.boolean(
                                        "deleted",
                                    )
                                ) {
                                    "(deleted)"
                                } else {
                                    val text = message.string("text").orEmpty()
                                    if (exceeds(text, ConversationSummaries.MAX_BODY)) cut = true
                                    clip(text, ConversationSummaries.MAX_BODY)
                                },
                            )
                            if (outgoing) message.string("status")?.takeIf(String::isNotBlank)?.let { put("status", it) }
                            val attachments = message.array("attachments").mapNotNull { it as? JsonObject }.map(::attachment)
                            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
                            val reactions =
                                message.array("reactions").mapNotNull { it as? JsonObject }.mapNotNull { reaction ->
                                    reaction.string("emoji")?.let { emoji ->
                                        "$emoji ×${reaction.array("participants").size.coerceAtLeast(1)}"
                                    }
                                }
                            if (reactions.isNotEmpty()) put("reactions", JsonArray(reactions.map(::JsonPrimitive)))
                        }
                    }.toMutableList()

            fun payload() =
                buildJsonObject {
                    put("conversation", conversation?.title() ?: conversationId)
                    put("participants", JsonArray((conversation?.others.orEmpty()).map { JsonPrimitive(it.listed) }))
                    put("messages", JsonArray(messages))
                }
            val fetched = messages.size
            while (payload().toString().length > MAX_READ_CHARS && messages.size > 1) messages.removeAt(0)
            val dropped = fetched - messages.size
            ExecutionOutcome(
                InvocationStatus.COMPLETED,
                "Recent ${bridge.label} messages, oldest first; status is what the bridge's server reported for messages you sent. " +
                    (if (messages.isEmpty()) "The bridge holds no messages for this conversation yet. " else "") +
                    (if (dropped > 0) leftOut(dropped) else "") +
                    (if (cut) ConversationSummaries.CUT + " " else "") +
                    "External ${bridge.label} data: " + payload(),
            )
        }

    suspend fun send(
        bridge: Bridge,
        target: Target,
        message: String,
        key: String,
    ): ExecutionOutcome =
        guarded(bridge) {
            if (message.isBlank() || message.length > MAX_MESSAGE || message.any { it.isISOControl() && it != '\n' && it != '\t' }) {
                throw BridgeError(InvocationStatus.NOT_EXECUTED, "Enter a nonempty message of at most $MAX_MESSAGE characters.")
            }
            val status = status(bridge)
            if (status.state in REFUSING_STATES) {
                throw BridgeError(
                    InvocationStatus.NOT_EXECUTED,
                    "${bridge.label} is not available: ${status.summary}. " +
                        (if (status.state == "authentication_required") "Re-pair the bridge with its account. " else "") +
                        "Nothing was sent.",
                )
            }
            val conversationId =
                when (target) {
                    is Target.Conversation -> {
                        target.id
                    }

                    is Target.Recipients -> {
                        existingConversation(bridge, target.numbers)
                            ?: createConversation(bridge, target.numbers, "$key-chat")
                    }
                }
            val destination = conversation(bridge, conversationId)?.title() ?: describe(target, conversationId)
            val body = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId), "text" to JsonPrimitive(message))).toString()
            val queued = queue(bridge, listOf("v1", "messages"), body, key)
            val outcome = awaitOutbox(bridge, key, queued) { it.state in TERMINAL_STATES }
            when (outcome?.state) {
                "accepted", "confirmed" -> {
                    ExecutionOutcome(
                        InvocationStatus.COMPLETED,
                        "Sent to $destination on ${bridge.label}: the ${bridge.label} server accepted it. " +
                            "This is not a delivery or read receipt; the conversation history shows its later status.",
                    )
                }

                "rejected" -> {
                    ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "${bridge.label} refused the message to $destination" + detail(outcome) + " Nothing was sent.",
                    )
                }

                "canceled" -> {
                    ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "The message to $destination was canceled at the bridge before it was sent.",
                    )
                }

                "ambiguous" -> {
                    ExecutionOutcome(
                        InvocationStatus.UNKNOWN,
                        "Whether the message to $destination reached ${bridge.label} is unknown" + detail(outcome) +
                            " Check the conversation before sending anything again; EVA does not resend it.",
                    )
                }

                else -> {
                    val current = runCatching { status(bridge) }.getOrNull()
                    ExecutionOutcome(
                        InvocationStatus.HANDED_OFF,
                        "The message to $destination is queued at the ${bridge.label} bridge and had not been sent yet" +
                            (current?.let { " (bridge state: ${it.summary})" } ?: "") +
                            ". The bridge keeps trying; read the conversation later to see whether it was sent.",
                    )
                }
            }
        }

    private suspend fun status(bridge: Bridge): Status {
        val body = json(get(bridge, listOf("v1", "status")), bridge)
        return Status(
            body.string("state").orEmpty().ifEmpty { "unknown" },
            body.string("reason"),
            body.string("detail")?.let { clip(it, 200) },
            body.boolean("transport_connected"),
            body.boolean("phone_responsive"),
        )
    }

    private suspend fun conversations(
        bridge: Bridge,
        query: String?,
        limit: Int,
    ): List<Conversation> {
        val parameters =
            buildMap {
                query?.let { put("q", it) }
                put("limit", limit.toString())
            }
        val body = json(get(bridge, listOf("v1", "conversations"), parameters), bridge)
        return body.array("conversations").mapNotNull { (it as? JsonObject)?.let(::conversation) }.also { remember(bridge, it) }
    }

    private suspend fun contacts(
        bridge: Bridge,
        query: String,
    ): List<Pair<String, String>> {
        val response =
            runCatching { get(bridge, listOf("v1", "contacts"), mapOf("q" to query, "limit" to MAX_CONTACTS.toString())) }.getOrNull()
                ?: return emptyList()
        if (response.status != 200) return emptyList()
        val body = runCatching { json(response, bridge) }.getOrNull() ?: return emptyList()
        return body.array("contacts").mapNotNull { it as? JsonObject }.mapNotNull { contact ->
            val address = contact.string("address")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            (contact.string("name")?.takeIf(String::isNotBlank) ?: address) to address
        }
    }

    /** The conversation as last listed by the bridge, fetched again by a bounded listing when unseen. */
    private suspend fun conversation(
        bridge: Bridge,
        id: String,
    ): Conversation? {
        synchronized(known) { known[key(bridge, id)] }?.let { return it }
        runCatching { conversations(bridge, null, SEARCH_PAGE) }
        return synchronized(known) { known[key(bridge, id)] }
    }

    private suspend fun existingConversation(
        bridge: Bridge,
        numbers: List<String>,
    ): String? =
        conversations(bridge, numbers.first().filter(Char::isDigit), SEARCH_PAGE)
            .firstOrNull { conversation ->
                conversation.others.size == numbers.size &&
                    numbers.all { number -> conversation.others.any { sameNumber(it.address, number) } }
            }?.id

    private suspend fun createConversation(
        bridge: Bridge,
        numbers: List<String>,
        key: String,
    ): String {
        val body = JsonObject(mapOf("recipients" to JsonArray(numbers.map(::JsonPrimitive)))).toString()
        val queued = queue(bridge, listOf("v1", "conversations"), body, key)
        val outcome = awaitOutbox(bridge, key, queued) { it.conversationId != null || it.state in TERMINAL_STATES }
        val people = numbers.joinToString(", ")
        outcome?.conversationId?.takeIf(String::isNotBlank)?.let { return it }
        throw when (outcome?.state) {
            "rejected" -> {
                BridgeError(
                    InvocationStatus.NOT_EXECUTED,
                    "${bridge.label} cannot start a chat with $people" + detail(outcome) + " Nothing was sent.",
                )
            }

            "ambiguous" -> {
                BridgeError(
                    InvocationStatus.NOT_EXECUTED,
                    "Starting a ${bridge.label} chat with $people had an unknown outcome, so the message was not sent. " +
                        "Search conversations again before trying.",
                )
            }

            "canceled" -> {
                BridgeError(
                    InvocationStatus.NOT_EXECUTED,
                    "Starting the ${bridge.label} chat with $people was canceled at the bridge; nothing was sent.",
                )
            }

            else -> {
                BridgeError(
                    InvocationStatus.NOT_EXECUTED,
                    "The ${bridge.label} bridge is still starting the chat with $people, so the message was not sent. Try again shortly.",
                )
            }
        }
    }

    /**
     * Queues one durable operation. A lost answer is resolved by reading the key back, never by
     * sending again: the bridge remembers the key, so a record found there is the one just queued.
     */
    private suspend fun queue(
        bridge: Bridge,
        path: List<String>,
        body: String,
        key: String,
    ): Outbox? {
        val response =
            try {
                post(bridge, path, body, key)
            } catch (error: CancellationException) {
                throw error
            } catch (_: BindingNotSubmitted) {
                throw BridgeError(InvocationStatus.NOT_EXECUTED, notSubmitted(bridge))
            } catch (_: Exception) {
                val lookup = runCatching { outbox(bridge, key) }
                if (lookup.isFailure) {
                    throw BridgeError(
                        InvocationStatus.UNKNOWN,
                        "The ${bridge.label} bridge did not answer, and whether it kept the message is unknown. " +
                            "Check the conversation before sending anything again.",
                    )
                }
                return lookup.getOrNull()
                    ?: throw BridgeError(
                        InvocationStatus.NOT_EXECUTED,
                        "The ${bridge.label} bridge did not answer and holds nothing for this request. Nothing was sent.",
                    )
            }
        return when (response.status) {
            200, 202 -> parseOutbox(json(response, bridge))

            404 -> throw BridgeError(InvocationStatus.FAILED, missing(bridge))

            409 -> throw BridgeError(
                InvocationStatus.NOT_EXECUTED,
                "The ${bridge.label} bridge already holds a different operation for this request. Nothing new was sent.",
            )

            else -> throw BridgeError(InvocationStatus.NOT_EXECUTED, refusal(bridge, response) + " Nothing was sent.")
        }
    }

    private suspend fun awaitOutbox(
        bridge: Bridge,
        key: String,
        first: Outbox?,
        done: (Outbox) -> Boolean,
    ): Outbox? {
        var record = first
        val deadline = clock() + sendWaitMillis
        while (record == null || !done(record)) {
            if (clock() >= deadline) break
            delay(pollMillis)
            record = runCatching { outbox(bridge, key) }.getOrNull() ?: record
        }
        return record
    }

    private suspend fun outbox(
        bridge: Bridge,
        key: String,
    ): Outbox? {
        val response = get(bridge, listOf("v1", "outbox", key))
        if (response.status == 404) return null
        return parseOutbox(json(response, bridge))
    }

    private fun parseOutbox(body: JsonObject) =
        Outbox(body.string("state").orEmpty(), body.string("conversation_id")?.takeIf(String::isNotBlank), body.string("detail"))

    private suspend fun get(
        bridge: Bridge,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
    ): HttpResponse =
        execute(bridge, HttpRequest(bridge.definition.origin, url(bridge, path, query), "GET", null, bridge.name, READ_BYTES, "bearer"))

    private suspend fun post(
        bridge: Bridge,
        path: List<String>,
        body: String,
        key: String,
    ): HttpResponse =
        execute(
            bridge,
            HttpRequest(
                bridge.definition.origin,
                url(bridge, path),
                "POST",
                body,
                bridge.name,
                READ_BYTES,
                "bearer",
                mapOf("Idempotency-Key" to key),
            ),
        )

    private suspend fun execute(
        bridge: Bridge,
        request: HttpRequest,
    ): HttpResponse {
        val response = http.execute(request, requestTimeoutMillis)
        if (response.status == 401 || response.status == 403) {
            throw BridgeError(
                InvocationStatus.NOT_EXECUTED,
                "The ${bridge.label} bridge rejected EVA's saved token. Enter the current token in Settings → Messaging.",
            )
        }
        return response
    }

    private fun url(
        bridge: Bridge,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
    ): String {
        val builder =
            bridge.definition.origin
                .toHttpUrl()
                .newBuilder()
        path.forEach(builder::addPathSegment)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    private fun json(
        response: HttpResponse,
        bridge: Bridge,
    ): JsonObject {
        if (response.status !in 200..299) throw BridgeError(InvocationStatus.FAILED, refusal(bridge, response))
        return runCatching { BoundedJson.parse(response.body, READ_BYTES) as? JsonObject }.getOrNull()
            ?: throw BridgeError(InvocationStatus.FAILED, "The ${bridge.label} bridge answered with something other than its API.")
    }

    /** Transport details never reach the model; a refusal before submission and a failed read are told apart. */
    private suspend fun guarded(
        bridge: Bridge,
        action: suspend () -> ExecutionOutcome,
    ): ExecutionOutcome =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: BridgeError) {
            ExecutionOutcome(error.status, error.message.orEmpty())
        } catch (_: BindingNotSubmitted) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, notSubmitted(bridge))
        } catch (_: Exception) {
            ExecutionOutcome(InvocationStatus.FAILED, unreachable(bridge))
        }

    private fun notSubmitted(bridge: Bridge): String =
        "No usable token is saved for ${bridge.label}. Enter it in Settings → Messaging. Nothing was submitted."

    private fun unreachable(bridge: Bridge): String =
        "The ${bridge.label} bridge could not be reached. Check that it is running and reachable from this phone."

    private fun refusal(
        bridge: Bridge,
        response: HttpResponse,
    ): String {
        val text = clip(response.body.filterNot(Char::isISOControl).trim(), 200)
        return "The ${bridge.label} bridge answered HTTP ${response.status}" + (if (text.isEmpty()) "." else ": \"$text\".")
    }

    private fun detail(outcome: Outbox?): String = outcome?.detail?.takeIf(String::isNotBlank)?.let { ": \"${clip(it, 200)}\"." } ?: "."

    private fun missing(bridge: Bridge) = "The ${bridge.label} bridge has no conversation with that reference. Search conversations again."

    private fun describe(
        target: Target,
        conversationId: String,
    ) = when (target) {
        is Target.Recipients -> target.numbers.joinToString(", ")
        is Target.Conversation -> conversationId
    }

    private fun row(
        bridge: Bridge,
        conversation: Conversation,
        now: Long,
    ): JsonObject =
        buildJsonObject {
            put("conversationRef", reference(bridge.name, conversation.id))
            put("name", conversation.title())
            put("participants", JsonArray(conversation.others.map { JsonPrimitive(it.listed) }))
            if (conversation.isGroup) put("group", true)
            put("lastActivity", conversation.updatedMillis?.let { ConversationSummaries.ago(it, now) } ?: "unknown")
            put("unread", conversation.unread)
            if (conversation.readOnly) put("readOnly", true)
            if (conversation.preview.isNotBlank()) put("preview", clip(preview(conversation), MAX_PREVIEW))
        }

    private fun preview(conversation: Conversation): String {
        val speaker =
            when {
                conversation.previewDirection == "outgoing" -> "You"
                else -> conversation.sender(conversation.previewSenderId)?.label
            }
        return (speaker?.let { "$it: " } ?: "") + conversation.preview
    }

    private fun previewCut(conversation: Conversation): Boolean =
        conversation.preview.isNotBlank() && exceeds(preview(conversation), MAX_PREVIEW)

    private fun conversation(record: JsonObject): Conversation? {
        val id = record.string("id")?.takeIf(String::isNotBlank) ?: return null
        return Conversation(
            id = id,
            name = record.string("name").orEmpty(),
            preview = record.string("preview").orEmpty(),
            updatedMillis = record.time("updated"),
            unread = record.boolean("unread"),
            readOnly = record.boolean("read_only"),
            participants =
                record.array("participants").mapNotNull { it as? JsonObject }.map { participant ->
                    Participant(
                        participant.string("id").orEmpty(),
                        participant.string("name").orEmpty(),
                        participant.string("address")?.takeIf(String::isNotBlank),
                        participant.boolean("is_me"),
                    )
                },
            previewSenderId = record.string("preview_sender_id"),
            previewDirection = record.string("preview_direction"),
        )
    }

    private fun remember(
        bridge: Bridge,
        conversations: List<Conversation>,
    ) {
        synchronized(known) {
            conversations.forEach { known[key(bridge, it.id)] = it }
            while (known.size > KNOWN_LIMIT) known.remove(known.keys.first())
        }
    }

    private fun key(
        bridge: Bridge,
        id: String,
    ) = reference(bridge.name, id)

    private fun attachment(record: JsonObject): String {
        val mime = record.string("mime").orEmpty()
        val kind =
            when (mime.substringBefore('/')) {
                "image" -> "Photo"
                "video" -> "Video"
                "audio" -> "Audio message"
                else -> "Attachment"
            }
        val name = record.string("name")?.takeIf(String::isNotBlank)
        return listOfNotNull(kind, name?.let { clip(it, 80) }, mime.takeIf(String::isNotBlank)).joinToString(" ")
    }

    private fun sameNumber(
        address: String?,
        number: String,
    ): Boolean = address != null && MessageRecipients.normalize(address) == MessageRecipients.normalize(number)

    private fun exceeds(
        value: String,
        max: Int,
    ) = value.replace(Regex("\\s+"), " ").trim().length > max

    private fun clip(
        value: String,
        max: Int,
    ): String {
        val flattened = value.replace(Regex("\\s+"), " ").trim()
        if (flattened.length <= max) return flattened
        return flattened.take(max - 1).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.trimEnd() + "…"
    }

    companion object {
        const val REFERENCE_PREFIX = "bridge:"
        const val SEND_WAIT_MILLIS = 20_000L
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val MAX_MESSAGE = 2_000
        private const val READ_BYTES = 1_048_576
        private const val KNOWN_LIMIT = 500
        private const val SEARCH_PAGE = 100
        private const val MAX_CONTACTS = 5
        private const val MAX_SEARCH_CHARS = 4_000
        private const val MAX_READ_CHARS = 6_000
        private const val MAX_PREVIEW = 160

        fun furtherConversations(count: Int) =
            "$count further ${if (count == 1) "conversation was" else "conversations were"} not shown; " +
                "narrow the search to see ${if (count == 1) "it" else "them"}. "

        fun leftOut(count: Int) =
            "$count older ${if (count == 1) "message was" else "messages were"} left out to fit EVA's result budget; " +
                "ask for a smaller limit. "

        private val TERMINAL_STATES = setOf("accepted", "confirmed", "rejected", "ambiguous", "canceled")
        private val REFUSING_STATES = setOf("authentication_required", "storage_failed")

        fun reference(
            name: String,
            conversationId: String,
        ) = "$REFERENCE_PREFIX$name:$conversationId"

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.content == "true"

        private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

        private fun JsonObject.time(name: String): Long? =
            string(name)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    }
}

package com.colonelpanic.eva.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One raw provider wire event, reduced to its type, identities, status, and bounded text.
 * Audio payloads and session bodies (instructions, tool schemas) are never kept. Consecutive
 * deltas for the same item are coalesced, so token streams do not flush the ring.
 */
data class ProviderWireEvent(
    val atMillis: Long,
    val lastAtMillis: Long,
    val connection: String,
    val outbound: Boolean,
    val type: String,
    val ids: Map<String, String>,
    val status: String?,
    /** Transcript, text, arguments, or error text; private export only, never Logcat. */
    val text: String?,
    val count: Int = 1,
)

/**
 * The last [capacity] raw Realtime events, in memory only. Recording extracts a handful of fields
 * from an already-parsed object; redaction happens once, when an export is assembled.
 */
class ProviderEventLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<ProviderWireEvent>()

    fun record(
        connection: String,
        outbound: Boolean,
        message: JsonObject,
    ) {
        val type = message.text("type") ?: "unknown"
        val response = message["response"] as? JsonObject
        val item = message["item"] as? JsonObject
        val error = message["error"] as? JsonObject
        val ids =
            buildMap {
                ID_KEYS.forEach { key -> message.text(key)?.let { put(key, it) } }
                response?.text("id")?.let { put("response.id", it) }
                (response?.get("metadata") as? JsonObject)?.forEach { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { put("metadata.$key", it.take(MAX_ID_CHARS)) }
                }
                item?.let { body ->
                    listOf("id", "type", "role", "call_id", "name", "status").forEach { key ->
                        body.text(key)?.let { put("item.$key", it) }
                    }
                }
                (message["session"] as? JsonObject)?.let { session ->
                    session.text("id")?.let { put("session.id", it) }
                    (session["tools"] as? JsonArray)?.let { put("session.tools", it.size.toString()) }
                }
                error?.let { body ->
                    listOf("type", "code", "event_id", "param").forEach { key -> body.text(key)?.let { put("error.$key", it) } }
                }
            }
        val status =
            response?.text("status")?.let { status ->
                ((response["status_details"] as? JsonObject)?.let { it.text("reason") ?: it.text("type") })?.let { "$status ($it)" }
                    ?: status
            }
        val text =
            if ("audio" in type && "transcript" !in type) {
                null
            } else {
                error?.text("message")
                    ?: message.text("transcript")
                    ?: message.text("delta")
                    ?: message.text("text")
                    ?: message.text("arguments")
                    ?: item?.let(::itemText)
            }?.take(MAX_TEXT_CHARS)
        val now = clock()
        synchronized(events) {
            val last = events.lastOrNull()
            if (last != null && type.endsWith(".delta") && last.type == type && last.connection == connection &&
                last.ids["item_id"] == ids["item_id"] && last.ids["response_id"] == ids["response_id"]
            ) {
                events[events.lastIndex] =
                    last.copy(
                        lastAtMillis = now,
                        text = ((last.text.orEmpty()) + text.orEmpty()).take(MAX_TEXT_CHARS),
                        count = last.count + 1,
                    )
                return
            }
            events.addLast(ProviderWireEvent(now, now, connection, outbound, type, ids, status, text))
            while (events.size > capacity) events.removeFirst()
        }
    }

    /** For an outbound string; very large configuration events keep only their type. */
    fun recordOutbound(
        connection: String,
        raw: String,
        parse: (String) -> JsonObject?,
    ) {
        val message =
            if (raw.length > MAX_PARSED_OUTBOUND) {
                JsonObject(mapOf("type" to JsonPrimitive(TYPE.find(raw)?.groupValues?.get(1) ?: "unknown")))
            } else {
                parse(raw) ?: return
            }
        record(connection, outbound = true, message)
    }

    fun snapshot(): List<ProviderWireEvent> = synchronized(events) { events.toList() }

    private fun itemText(item: JsonObject): String? =
        (item["content"] as? JsonArray)
            ?.mapNotNull { part -> (part as? JsonObject)?.let { it.text("text") ?: it.text("transcript") } }
            ?.joinToString(" ")
            ?.ifEmpty { null }
            ?: item.text("output")
            ?: item.text("arguments")

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_CAPACITY = 1_000
        const val MAX_TEXT_CHARS = 2_000
        private const val MAX_ID_CHARS = 200
        private const val MAX_PARSED_OUTBOUND = 65_536
        private val ID_KEYS = listOf("event_id", "response_id", "item_id", "previous_item_id", "call_id", "output_index", "content_index")
        private val TYPE = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")

        /** Shared by every Realtime session in the process. */
        val realtime = ProviderEventLog()

        fun toJson(event: ProviderWireEvent): JsonObject =
            buildJsonObject {
                put(
                    "at",
                    java.time.Instant
                        .ofEpochMilli(event.atMillis)
                        .toString(),
                )
                if (event.count > 1) {
                    put(
                        "lastAt",
                        java.time.Instant
                            .ofEpochMilli(event.lastAtMillis)
                            .toString(),
                    )
                    put("coalesced", event.count)
                }
                put("connection", event.connection)
                put("direction", if (event.outbound) "out" else "in")
                put("type", event.type)
                put("ids", JsonObject(event.ids.mapValues { JsonPrimitive(it.value) }))
                event.status?.let { put("status", it) }
                event.text?.let { put("text", it) }
            }
    }
}

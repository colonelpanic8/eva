package com.colonelpanic.eva.diagnostics

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.conversation.ConversationStore
import com.colonelpanic.eva.conversation.SessionCatalogRecord
import com.colonelpanic.eva.conversation.TaskSnapshot
import com.colonelpanic.eva.conversation.Thread
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.Turn
import com.colonelpanic.eva.providers.toolImages
import com.colonelpanic.eva.providers.withoutToolImages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** Everything a thread's diagnostics export is assembled from, read once. */
data class DiagnosticsSnapshot(
    val generatedAtMillis: Long,
    val environment: Map<String, String>,
    val threadId: String,
    val thread: Thread?,
    /** The newest items, oldest first. */
    val items: List<ThreadItem>,
    val totalItems: Int,
    val turns: List<Turn>,
    val receipts: Map<String, InvocationRecord>,
    val sessions: List<SessionCatalogRecord>,
    val tasks: List<TaskSnapshot>,
    val trace: List<TraceEvent>,
    /** Recent raw Realtime wire events across sessions, oldest first. */
    val providerEvents: List<ProviderWireEvent> = emptyList(),
)

/**
 * A thread's evidence as one JSON document: items in order joined with their receipts, turns,
 * the catalogs each connection was offered, task snapshots, device-task steps, and recent trace
 * events. Bounded by [MAX_ITEMS], [MAX_SESSIONS], and [MAX_TEXT_CHARS]; whatever a bound leaves out
 * is counted in `bounds`. The whole document passes through [Redactor] last.
 */
object DiagnosticsExport {
    const val FORMAT = "eva-diagnostics"
    const val VERSION = 1
    const val MAX_ITEMS = 400
    const val MAX_SESSIONS = ConversationStore.SESSION_CATALOG_LIMIT
    const val MAX_TEXT_CHARS = 32_000
    const val REDACTION_RULE =
        "Credential values (tokens, keys, passwords, authorization headers, cookies, stored secrets) are replaced with " +
            Redactor.REDACTED + ". Message text, action arguments, and results are included."

    private val json = Json { prettyPrint = true }

    suspend fun collect(
        store: ConversationStore,
        repository: InvocationRepository,
        threadId: String,
        environment: Map<String, String>,
        tasks: List<TaskSnapshot>,
        trace: List<TraceEvent>,
        nowMillis: Long,
        maxItems: Int = MAX_ITEMS,
        providerEvents: List<ProviderWireEvent> = emptyList(),
    ): DiagnosticsSnapshot {
        val items = store.items(threadId, maxItems)
        val callIds = items.filterIsInstance<ThreadItem.ActionCall>().map { it.callId }
        return DiagnosticsSnapshot(
            nowMillis,
            environment,
            threadId,
            store.thread(threadId),
            items,
            maxOf(store.itemCount(threadId), items.size),
            store.turns(threadId),
            if (callIds.isEmpty()) emptyMap() else repository.byCallIds(callIds),
            store.sessionCatalogs(threadId, MAX_SESSIONS),
            tasks.filter { it.threadId == threadId },
            trace,
            providerEvents,
        )
    }

    fun assemble(
        snapshot: DiagnosticsSnapshot,
        redactor: Redactor,
    ): JsonObject {
        val omitted = snapshot.totalItems - snapshot.items.size
        val document =
            buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("summary", JsonArray(summary(snapshot).map(::JsonPrimitive)))
                put("generatedAt", iso(snapshot.generatedAtMillis))
                put("redaction", REDACTION_RULE)
                put("environment", JsonObject(snapshot.environment.mapValues { JsonPrimitive(it.value) }))
                putJsonObject("thread") {
                    put("id", snapshot.threadId)
                    snapshot.thread?.let {
                        put("title", it.title)
                        put("createdAt", iso(it.createdAtMillis))
                        put("updatedAt", iso(it.updatedAtMillis))
                    }
                }
                putJsonObject("bounds") {
                    putJsonObject("items") {
                        put("total", snapshot.totalItems)
                        put("included", snapshot.items.size)
                        put("omitted", omitted)
                        if (omitted > 0) put("statement", "$omitted earlier items omitted")
                    }
                    putJsonObject("sessions") {
                        put("included", snapshot.sessions.size)
                        put("limit", MAX_SESSIONS)
                    }
                    put("textCharacters", MAX_TEXT_CHARS)
                    put("traceEvents", snapshot.trace.size)
                    put("providerEvents", snapshot.providerEvents.size)
                    put("providerEventCapacity", ProviderEventLog.DEFAULT_CAPACITY)
                    put("providerEventTextCharacters", ProviderEventLog.MAX_TEXT_CHARS)
                }
                putJsonArray("turns") {
                    snapshot.turns.forEach { turn ->
                        add(
                            buildJsonObject {
                                put("id", turn.id)
                                put("status", turn.status.name)
                                put("request", bounded(turn.request))
                                put("createdAt", iso(turn.createdAtMillis))
                            },
                        )
                    }
                }
                putJsonArray("sessions") { snapshot.sessions.forEach { add(session(it)) } }
                putJsonArray("items") {
                    snapshot.items.forEachIndexed { index, item -> add(item(omitted + index, item, snapshot.receipts)) }
                }
                putJsonArray("tasks") { snapshot.tasks.forEach { add(task(it)) } }
                putJsonArray("deviceTasks") {
                    snapshot.items
                        .filterIsInstance<ThreadItem.ActionCall>()
                        .filter { it.capabilityId == CapabilityRegistry.DEVICE_TASK }
                        .forEach { call -> add(deviceTask(call, snapshot.receipts[call.callId])) }
                }
                put("trace", traceEvents(snapshot.trace))
                put("providerEvents", JsonArray(snapshot.providerEvents.map(ProviderEventLog::toJson)))
            }
        return redactor.json(document).jsonObject
    }

    /** Only the recent trace, for a report that is not about one thread. */
    fun logs(
        environment: Map<String, String>,
        trace: List<TraceEvent>,
        nowMillis: Long,
        redactor: Redactor,
        providerEvents: List<ProviderWireEvent> = emptyList(),
    ): JsonObject =
        redactor
            .json(
                buildJsonObject {
                    put("format", FORMAT)
                    put("version", VERSION)
                    put(
                        "summary",
                        buildJsonArray {
                            add(JsonPrimitive("EVA recent logs: ${trace.size} events"))
                            add(JsonPrimitive(environmentLine(environment)))
                            add(JsonPrimitive("Credential values are redacted."))
                        },
                    )
                    put("generatedAt", iso(nowMillis))
                    put("redaction", REDACTION_RULE)
                    put("environment", JsonObject(environment.mapValues { JsonPrimitive(it.value) }))
                    put("trace", traceEvents(trace))
                    // Without conversation content: identities, types, and statuses only.
                    put("providerEvents", JsonArray(providerEvents.map { ProviderEventLog.toJson(it.copy(text = null)) }))
                },
            ).jsonObject

    fun render(document: JsonObject): String = json.encodeToString(JsonObject.serializer(), document) + "\n"

    /** The `summary` lines of an assembled document, for a share message or clipboard. */
    fun summaryText(document: JsonObject): String = document["summary"]?.jsonArray.orEmpty().joinToString("\n") { it.jsonPrimitive.content }

    fun summary(snapshot: DiagnosticsSnapshot): List<String> {
        val omitted = snapshot.totalItems - snapshot.items.size
        val calls = snapshot.items.filterIsInstance<ThreadItem.ActionCall>()
        val statuses =
            calls
                .groupingBy { snapshot.receipts[it.callId]?.status?.name ?: "NO_RECEIPT" }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString { "${it.key} ${it.value}" }
        val lastSession = snapshot.sessions.lastOrNull()
        return listOfNotNull(
            "EVA diagnostics for thread \"${snapshot.thread?.title ?: "unknown"}\" (${snapshot.threadId})",
            environmentLine(snapshot.environment),
            "Generated ${iso(snapshot.generatedAtMillis)}",
            "${snapshot.items.size} of ${snapshot.totalItems} items" + (if (omitted > 0) " ($omitted earlier items omitted)" else "") +
                ", ${snapshot.turns.size} turns, ${calls.size} actions" + (if (statuses.isEmpty()) "" else ": $statuses"),
            lastSession?.let {
                "${snapshot.sessions.size} sessions; last ${it.kind.name.lowercase()}" + (it.model?.let { model -> " $model" } ?: "") +
                    " offered ${it.tools.size} tools, ${it.excludedTools.size} excluded"
            },
            snapshot.tasks.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Active tasks: ") { "${it.taskId} ${it.kind} ${it.state}" },
            "${snapshot.trace.size} recent trace events, ${snapshot.providerEvents.size} recent Realtime wire events",
            "Credential values are redacted; message content is included.",
        )
    }

    private fun environmentLine(environment: Map<String, String>) =
        "EVA ${environment["appVersion"] ?: "?"} (${environment["versionCode"] ?: "?"}, ${environment["buildType"] ?: "?"}) on " +
            "${environment["device"] ?: "?"}, ${environment["os"] ?: "?"}"

    private fun item(
        index: Int,
        item: ThreadItem,
        receipts: Map<String, InvocationRecord>,
    ): JsonObject =
        buildJsonObject {
            put("index", index)
            put("id", item.id)
            put(
                "type",
                when (item) {
                    is ThreadItem.Question -> "question"
                    is ThreadItem.UserMessage -> "user"
                    is ThreadItem.AssistantMessage -> "assistant"
                    is ThreadItem.ActionCall -> "action"
                    is ThreadItem.TextLeg -> "text_leg"
                    is ThreadItem.Notice -> "notice"
                },
            )
            put("at", iso(item.createdAtMillis))
            item.turnId?.let { put("turnId", it) }
            when (item) {
                is ThreadItem.Question -> {
                    put("questionId", item.evidence.questionId)
                    put("resolution", item.evidence.resolution.name)
                }

                is ThreadItem.UserMessage -> {
                    put("spoken", item.spoken)
                    put("text", bounded(item.text))
                }

                is ThreadItem.AssistantMessage -> {
                    put("spoken", item.spoken)
                    if (item.truncated) put("truncated", true)
                    put("text", bounded(item.text))
                }

                is ThreadItem.ActionCall -> {
                    put("callId", item.callId)
                    put("capabilityId", item.capabilityId)
                    put("title", item.title)
                    item.legId?.let { put("legId", it) }
                    item.initiator?.let { put("initiator", it.toJson()) }
                    put("arguments", JsonObject(item.arguments.mapValues { JsonPrimitive(bounded(it.value)) }))
                    put("receipt", receipts[item.callId]?.let(::receipt) ?: JsonPrimitive("missing: no journal record for this call"))
                }

                is ThreadItem.TextLeg -> {
                    item.task?.let { put("task", bounded(it)) }
                    put("historyItems", item.historyItems)
                    put("instructions", bounded(item.instructions))
                }

                is ThreadItem.Notice -> {
                    put("kind", item.kind.name)
                    put("text", bounded(item.text))
                }
            }
        }

    private fun receipt(record: InvocationRecord): JsonObject =
        buildJsonObject {
            put("status", record.status.name)
            put("message", bounded(record.message))
            put("capabilityId", record.capabilityId)
            record.title?.let { put("title", it) }
            put("catalogRevision", record.catalogRevision)
            put("claimedAt", iso(record.createdAtMillis))
            put("request", bounded(record.request))
            record.destination?.let { put("destination", it) }
            record.turnId?.let { put("turnId", it) }
            record.arguments?.let { arguments -> put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(bounded(it.value)) })) }
            record.initiator?.let { put("initiator", it.toJson()) }
            record.provenance?.let { put("provenance", it.toJson()) }
            record.data?.let {
                put("data", boundedJson(it.withoutToolImages()))
                val imageCount = it.toolImages().size
                if (imageCount > 0) put("imagesOmitted", imageCount)
            }
        }

    private fun session(record: SessionCatalogRecord): JsonObject =
        buildJsonObject {
            put("kind", record.kind.name)
            put("at", iso(record.createdAtMillis))
            record.turnId?.let { put("turnId", it) }
            record.legId?.let { put("legId", it) }
            record.model?.let { put("model", it) }
            put("catalogRevision", record.catalogRevision)
            put("toolCount", record.tools.size)
            putJsonArray("tools") {
                record.tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("id", tool.capabilityId)
                            put("title", tool.title)
                        },
                    )
                }
            }
            put("excludedTools", JsonArray(record.excludedTools.map(::JsonPrimitive)))
        }

    private fun task(task: TaskSnapshot): JsonObject =
        buildJsonObject {
            put("taskId", task.taskId)
            put("kind", task.kind.name)
            put("state", task.state.name)
            put("request", bounded(task.request))
            put("startedAt", iso(task.startedAt))
            put("lastProgressAt", iso(task.lastProgressAt))
            put("actionCount", task.actionCount)
            task.lastActionTitle?.let { put("lastActionTitle", it) }
            task.lastActionStatus?.let { put("lastActionStatus", it) }
            put("holdsDeviceLease", task.holdsDeviceLease)
            put("coverage", task.coverage.name)
            put("looksStuck", task.looksStuck)
        }

    private fun deviceTask(
        call: ThreadItem.ActionCall,
        receipt: InvocationRecord?,
    ): JsonObject =
        buildJsonObject {
            put("callId", call.callId)
            call.turnId?.let { put("turnId", it) }
            put("goal", bounded(call.arguments["goal"].orEmpty()))
            put("status", receipt?.status?.name ?: "NO_RECEIPT")
            val data = receipt?.data ?: return@buildJsonObject
            listOf("taskId", "revision", "status", "effects").forEach { key ->
                data[key]?.let {
                    put("task" + key.replaceFirstChar(Char::uppercase), it)
                }
            }
            putJsonArray("steps") {
                (data["steps"] as? JsonArray).orEmpty().forEach { step ->
                    val fields = step as? JsonObject ?: return@forEach
                    add(
                        buildJsonObject {
                            STEP_FIELDS.forEach { key -> fields[key]?.let { put(key, it) } }
                            (fields["result"] as? JsonPrimitive)?.let { put("result", bounded(it.content, STEP_RESULT_CHARS)) }
                        },
                    )
                }
            }
        }

    private fun traceEvents(trace: List<TraceEvent>) =
        JsonArray(
            trace.map { event ->
                buildJsonObject {
                    put("at", iso(event.atMillis))
                    put("level", event.level.name)
                    put("event", event.name)
                    put("fields", JsonObject(event.fields.mapValues { JsonPrimitive(it.value) }))
                }
            },
        )

    private fun boundedJson(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapValues { boundedJson(it.value) })
            is JsonArray -> JsonArray(value.map(::boundedJson))
            is JsonPrimitive -> if (value.isString) JsonPrimitive(bounded(value.content)) else value
        }

    private fun bounded(
        text: String,
        limit: Int = MAX_TEXT_CHARS,
    ): String = if (text.length <= limit) text else text.take(limit) + "…[${text.length - limit} more characters omitted]"

    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()

    private const val STEP_RESULT_CHARS = 600
    private val STEP_FIELDS = listOf("step", "revision", "kind", "callId", "observationMillis", "modelMillis", "actionMillis")
}

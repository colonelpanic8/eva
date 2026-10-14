package com.colonelpanic.eva.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executor

enum class TraceLevel { INFO, VERBOSE }

/**
 * One lifecycle event. Fields carry identities, kinds, statuses, counts, and EVA-authored reasons
 * only: never message text, tool arguments, or credentials. Values still pass through [Redactor].
 */
data class TraceEvent(
    val atMillis: Long,
    val level: TraceLevel,
    val name: String,
    val fields: Map<String, String>,
) {
    fun line(): String = (listOf(name) + fields.map { (key, value) -> "$key=$value" }).joinToString(" ")

    fun toJson(): JsonObject =
        buildJsonObject {
            put("at", atMillis)
            put("level", level.name)
            put("event", name)
            put("fields", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
        }

    companion object {
        fun fromJson(value: JsonObject): TraceEvent? =
            runCatching {
                TraceEvent(
                    value.getValue("at").jsonPrimitive.longOrNull ?: return null,
                    TraceLevel.valueOf(value.getValue("level").jsonPrimitive.content),
                    value.getValue("event").jsonPrimitive.content,
                    value.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content },
                )
            }.getOrNull()
    }
}

fun interface TraceSink {
    fun record(event: TraceEvent)
}

/**
 * EVA's lifecycle trace. Hosts install a [sink]; until then events are dropped. Verbose events are
 * recorded only while [verbose] is on.
 */
object EvaTrace {
    @Volatile var sink: TraceSink? = null

    @Volatile var verbose: Boolean = false

    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val redactor = Redactor()

    fun info(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) = emit(TraceLevel.INFO, name, fields)

    fun verbose(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (verbose) emit(TraceLevel.VERBOSE, name, fields)
    }

    private fun emit(
        level: TraceLevel,
        name: String,
        fields: Array<out Pair<String, Any?>>,
    ) {
        val target = sink ?: return
        val values =
            fields
                .mapNotNull { (key, value) -> value?.let { key to redactor.text(it.toString()).replace('\n', ' ').take(MAX_FIELD_CHARS) } }
                .toMap()
        runCatching { target.record(TraceEvent(clock(), level, name, values)) }
    }

    private const val MAX_FIELD_CHARS = 240
}

/**
 * A bounded ring of the most recent [capacity] events, mirrored to two rotating JSON-lines files in
 * private storage so a restarted process restores the full ring. Each file holds at most [capacity]
 * events and the older file is replaced whole, so disk use stays below twice the capacity.
 * Writes run on [writer].
 */
class TraceLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val directory: File? = null,
    private val writer: Executor = Executor(Runnable::run),
) : TraceSink {
    private val events = ArrayDeque<TraceEvent>()
    private var currentLines = 0

    init {
        require(capacity >= 1)
        directory?.let { dir ->
            val restored =
                listOf(File(dir, PREVIOUS), File(dir, CURRENT)).flatMap { file ->
                    if (!file.isFile) {
                        emptyList()
                    } else {
                        file.readLines().mapNotNull { line ->
                            runCatching { TraceEvent.fromJson(Json.parseToJsonElement(line).jsonObject) }.getOrNull()
                        }
                    }
                }
            restored.takeLast(capacity).forEach(events::addLast)
            currentLines = File(dir, CURRENT).takeIf { it.isFile }?.useLines { it.count() } ?: 0
        }
    }

    override fun record(event: TraceEvent) {
        synchronized(events) {
            events.addLast(event)
            while (events.size > capacity) events.removeFirst()
        }
        val dir = directory ?: return
        writer.execute {
            synchronized(this) {
                runCatching {
                    dir.mkdirs()
                    if (currentLines >= capacity) {
                        val previous = File(dir, PREVIOUS)
                        previous.delete()
                        File(dir, CURRENT).renameTo(previous)
                        currentLines = 0
                    }
                    File(dir, CURRENT).appendText(event.toJson().toString() + "\n")
                    currentLines++
                }
            }
        }
    }

    fun snapshot(): List<TraceEvent> = synchronized(events) { events.toList() }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
        const val CURRENT = "trace.jsonl"
        const val PREVIOUS = "trace.1.jsonl"
    }
}

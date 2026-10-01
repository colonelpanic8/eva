package com.colonelpanic.eva.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Durable threads, turns, and items. Shares the journal database with the invocation
 * repository. Action outcomes are joined from the journal by their turn and call identities.
 */
interface ConversationStore {
    /** Emits the id of a thread whose items or turns changed. */
    val changes: Flow<String>

    suspend fun createThread(title: String): Thread

    /** Newest updated first. */
    suspend fun threads(): List<Thread>

    suspend fun thread(id: String): Thread?

    suspend fun retitle(
        id: String,
        title: String,
    )

    /** Oldest first, the last [limit] items. */
    suspend fun items(
        threadId: String,
        limit: Int = DEFAULT_ITEM_LIMIT,
    ): List<ThreadItem>

    suspend fun turns(threadId: String): List<Turn>

    suspend fun openTurn(
        threadId: String,
        request: String,
        id: String,
    ): Turn

    suspend fun closeTurn(
        turnId: String,
        status: TurnStatus,
    )

    suspend fun append(item: ThreadItem)

    /** OPEN turns left by a dead process become INTERRUPTED; called once at application start. */
    suspend fun recoverInterrupted(): List<Turn>

    /**
     * Records the tool catalog a connection or text leg was offered, for diagnostics. Only the
     * newest [SESSION_CATALOG_LIMIT] records per thread are kept; older ones are dropped on insert.
     */
    suspend fun recordSessionCatalog(record: SessionCatalogRecord)

    /** Oldest first, the last [limit] catalog records. */
    suspend fun sessionCatalogs(
        threadId: String,
        limit: Int = DEFAULT_ITEM_LIMIT,
    ): List<SessionCatalogRecord>

    /** How many items the thread holds, so a bounded export can state what it omitted. */
    suspend fun itemCount(threadId: String): Int

    companion object {
        const val DEFAULT_ITEM_LIMIT = 200
        const val SESSION_CATALOG_LIMIT = 50
    }
}

/**
 * What one connection or background text leg was offered when it connected: provider-facing
 * tool identities and titles, the catalog revision, and the tools admission left out.
 */
data class SessionCatalogRecord(
    val id: String,
    val threadId: String,
    /** The turn a background text leg serves; null for an attached connection. */
    val turnId: String?,
    val createdAtMillis: Long,
    val kind: SessionKind,
    /** The [ThreadItem.TextLeg] id for a background leg. */
    val legId: String?,
    val model: String?,
    val catalogRevision: String,
    val tools: List<OfferedTool>,
    val excludedTools: List<String>,
)

data class OfferedTool(
    val capabilityId: String,
    val title: String,
)

enum class SessionKind { VOICE, TEXT, TEXT_LEG }

/** The JSON columns both journals store a [SessionCatalogRecord]'s lists in. */
object SessionCatalogColumns {
    fun tools(tools: List<OfferedTool>): String =
        JsonArray(
            tools.map { JsonObject(mapOf("capabilityId" to JsonPrimitive(it.capabilityId), "title" to JsonPrimitive(it.title))) },
        ).toString()

    fun tools(column: String): List<OfferedTool> =
        Json.parseToJsonElement(column).jsonArray.map {
            OfferedTool(
                it.jsonObject
                    .getValue("capabilityId")
                    .jsonPrimitive.content,
                it.jsonObject
                    .getValue("title")
                    .jsonPrimitive.content,
            )
        }

    fun ids(ids: List<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

    fun ids(column: String): List<String> = Json.parseToJsonElement(column).jsonArray.map { it.jsonPrimitive.content }
}

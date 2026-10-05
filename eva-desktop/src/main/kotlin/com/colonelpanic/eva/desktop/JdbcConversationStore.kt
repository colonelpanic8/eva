package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.conversation.ConversationStore
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.SessionCatalogColumns
import com.colonelpanic.eva.conversation.SessionCatalogRecord
import com.colonelpanic.eva.conversation.SessionKind
import com.colonelpanic.eva.conversation.Thread
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.Turn
import com.colonelpanic.eva.conversation.TurnStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Threads, turns, and their items, stored as the phone stores them. */
class JdbcConversationStore(
    private val journal: JdbcJournal,
    private val now: () -> Long = System::currentTimeMillis,
) : ConversationStore {
    override val changes =
        MutableSharedFlow<String>(extraBufferCapacity = CHANGE_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun createThread(title: String): Thread {
        val created =
            journal.transaction { db ->
                val stamp = maxOf(now(), db.latestUpdatedAt() + 1)
                Thread(UUID.randomUUID().toString(), title, stamp, stamp).also { thread ->
                    db.update(
                        "INSERT INTO threads (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        thread.id,
                        thread.title,
                        thread.createdAtMillis,
                        thread.updatedAtMillis,
                    )
                }
            }
        changes.tryEmit(created.id)
        return created
    }

    override suspend fun threads(): List<Thread> =
        journal.read { db ->
            db.query("SELECT * FROM threads ORDER BY updated_at DESC, rowid DESC") {
                it.thread()
            }
        }

    override suspend fun thread(id: String): Thread? =
        journal.read { db ->
            db
                .query("SELECT * FROM threads WHERE id = ?", id) {
                    it.thread()
                }.firstOrNull()
        }

    override suspend fun retitle(
        id: String,
        title: String,
    ) {
        journal.transaction { db ->
            check(db.update("UPDATE threads SET title = ? WHERE id = ?", title, id) == 1) { "Unknown thread: $id" }
            bump(db, id)
        }
        changes.tryEmit(id)
    }

    override suspend fun items(
        threadId: String,
        limit: Int,
    ): List<ThreadItem> {
        require(limit >= 0)
        return journal.read { db ->
            db.query("SELECT * FROM items WHERE thread_id = ? ORDER BY rowid DESC LIMIT ?", threadId, limit) { it.item() }.reversed()
        }
    }

    override suspend fun turns(threadId: String): List<Turn> =
        journal.read { db -> db.query("SELECT * FROM turns WHERE thread_id = ? ORDER BY rowid ASC", threadId) { it.turn() } }

    override suspend fun openTurn(
        threadId: String,
        request: String,
        id: String,
    ): Turn {
        val turn = Turn(id, threadId, request, TurnStatus.OPEN, now())
        journal.transaction { db ->
            db.update(
                "INSERT INTO turns (id, thread_id, request, status, created_at) VALUES (?, ?, ?, ?, ?)",
                turn.id,
                turn.threadId,
                turn.request,
                turn.status.name,
                turn.createdAtMillis,
            )
            bump(db, threadId)
        }
        changes.tryEmit(threadId)
        return turn
    }

    override suspend fun closeTurn(
        turnId: String,
        status: TurnStatus,
    ) {
        val threadId =
            journal.transaction { db ->
                val threadId =
                    checkNotNull(db.query("SELECT thread_id FROM turns WHERE id = ?", turnId) { it.getString(1) }.firstOrNull()) {
                        "Unknown turn: $turnId"
                    }
                check(db.update("UPDATE turns SET status = ? WHERE id = ?", status.name, turnId) == 1)
                bump(db, threadId)
                threadId
            }
        changes.tryEmit(threadId)
    }

    override suspend fun append(item: ThreadItem) {
        journal.transaction { db ->
            insert(db, item)
            bump(db, item.threadId)
        }
        changes.tryEmit(item.threadId)
    }

    override suspend fun recoverInterrupted(): List<Turn> {
        val recovered =
            journal.transaction { db ->
                val open = db.query("SELECT * FROM turns WHERE status = ? ORDER BY rowid ASC", TurnStatus.OPEN.name) { it.turn() }
                if (open.isNotEmpty()) {
                    db.update(
                        "UPDATE turns SET status = ? WHERE status = ?",
                        TurnStatus.INTERRUPTED.name,
                        TurnStatus.OPEN.name,
                    )
                }
                open.map { it.copy(status = TurnStatus.INTERRUPTED) }
            }
        recovered.map { it.threadId }.distinct().forEach(changes::tryEmit)
        return recovered
    }

    override suspend fun recordSessionCatalog(record: SessionCatalogRecord) {
        journal.transaction { db ->
            db.update(
                "INSERT INTO session_catalogs (id, thread_id, turn_id, created_at, kind, leg_id, model, catalog_revision, " +
                    "tools_json, excluded_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.id,
                record.threadId,
                record.turnId,
                record.createdAtMillis,
                record.kind.name,
                record.legId,
                record.model,
                record.catalogRevision,
                SessionCatalogColumns.tools(record.tools),
                SessionCatalogColumns.ids(record.excludedTools),
            )
            db.update(
                "DELETE FROM session_catalogs WHERE thread_id = ? AND rowid NOT IN " +
                    "(SELECT rowid FROM session_catalogs WHERE thread_id = ? ORDER BY rowid DESC LIMIT ?)",
                record.threadId,
                record.threadId,
                ConversationStore.SESSION_CATALOG_LIMIT,
            )
        }
    }

    override suspend fun sessionCatalogs(
        threadId: String,
        limit: Int,
    ): List<SessionCatalogRecord> {
        require(limit >= 0)
        return journal.read { db ->
            db
                .query("SELECT * FROM session_catalogs WHERE thread_id = ? ORDER BY rowid DESC LIMIT ?", threadId, limit) {
                    SessionCatalogRecord(
                        it.getString("id"),
                        it.getString("thread_id"),
                        it.nullableString("turn_id"),
                        it.getLong("created_at"),
                        SessionKind.valueOf(it.getString("kind")),
                        it.nullableString("leg_id"),
                        it.nullableString("model"),
                        it.getString("catalog_revision"),
                        SessionCatalogColumns.tools(it.getString("tools_json")),
                        SessionCatalogColumns.ids(it.getString("excluded_json")),
                    )
                }.reversed()
        }
    }

    override suspend fun itemCount(threadId: String): Int =
        journal.read { db -> db.query("SELECT COUNT(*) FROM items WHERE thread_id = ?", threadId) { it.getInt(1) }.single() }

    private fun bump(
        db: Connection,
        threadId: String,
    ) {
        val current =
            checkNotNull(db.query("SELECT updated_at FROM threads WHERE id = ?", threadId) { it.getLong(1) }.firstOrNull()) {
                "Unknown thread: $threadId"
            }
        val updatedAt = maxOf(now(), current + 1, db.latestUpdatedAt() + 1)
        check(db.update("UPDATE threads SET updated_at = ? WHERE id = ?", updatedAt, threadId) == 1)
    }

    private fun Connection.latestUpdatedAt(): Long = query("SELECT COALESCE(MAX(updated_at), 0) FROM threads") { it.getLong(1) }.single()

    private fun insert(
        db: Connection,
        item: ThreadItem,
    ) {
        val columns =
            linkedMapOf<String, Any?>(
                "id" to item.id,
                "thread_id" to item.threadId,
                "turn_id" to item.turnId,
                "created_at" to item.createdAtMillis,
            )
        when (item) {
            is ThreadItem.Question -> {
                columns += mapOf("type" to "question", "text" to item.evidence.encode())
            }

            is ThreadItem.UserMessage -> {
                columns += mapOf("type" to USER_MESSAGE, "text" to item.text, "spoken" to item.spoken.int())
            }

            is ThreadItem.AssistantMessage -> {
                columns +=
                    mapOf(
                        "type" to ASSISTANT_MESSAGE,
                        "text" to item.text,
                        "spoken" to item.spoken.int(),
                        "truncated" to item.truncated.int(),
                    )
            }

            is ThreadItem.ActionCall -> {
                columns +=
                    mapOf(
                        "type" to ACTION_CALL,
                        "call_id" to item.callId,
                        "capability_id" to item.capabilityId,
                        "title" to item.title,
                        "arguments" to Json.encodeToString(item.arguments),
                        "leg_id" to item.legId,
                        "initiator_json" to item.initiator?.toJson()?.toString(),
                    )
            }

            is ThreadItem.TextLeg -> {
                columns +=
                    mapOf(
                        "type" to TEXT_LEG,
                        "text" to item.task,
                        "instructions" to item.instructions,
                        "history_items" to item.historyItems,
                    )
            }

            is ThreadItem.Notice -> {
                columns += mapOf("type" to NOTICE, "notice_kind" to item.kind.name, "text" to item.text)
            }
        }
        db.update(
            "INSERT INTO items (${columns.keys.joinToString()}) VALUES (${columns.keys.joinToString { "?" }})",
            *columns.values.toTypedArray(),
        )
    }

    private fun ResultSet.thread() = Thread(getString("id"), getString("title"), getLong("created_at"), getLong("updated_at"))

    private fun ResultSet.turn() =
        Turn(getString("id"), getString("thread_id"), getString("request"), TurnStatus.valueOf(getString("status")), getLong("created_at"))

    private fun ResultSet.item(): ThreadItem {
        val id = getString("id")
        val threadId = getString("thread_id")
        val turnId = nullableString("turn_id")
        val createdAt = getLong("created_at")
        return when (val type = getString("type")) {
            "question" -> {
                ThreadItem.Question(
                    id,
                    threadId,
                    checkNotNull(turnId),
                    createdAt,
                    com.colonelpanic.eva.conversation.QuestionEvidence
                        .decode(getString("text")),
                )
            }

            USER_MESSAGE -> {
                ThreadItem.UserMessage(id, threadId, turnId, createdAt, getString("text"), flag("spoken"))
            }

            ASSISTANT_MESSAGE -> {
                ThreadItem.AssistantMessage(id, threadId, turnId, createdAt, getString("text"), flag("spoken"), flag("truncated"))
            }

            ACTION_CALL -> {
                ThreadItem.ActionCall(
                    id,
                    threadId,
                    turnId,
                    createdAt,
                    getString("call_id"),
                    getString("capability_id"),
                    getString("title"),
                    Json.decodeFromString(getString("arguments")),
                    nullableString("leg_id"),
                    nullableString("initiator_json")?.let { ActionInitiator.fromJson(Json.parseToJsonElement(it).jsonObject) },
                )
            }

            TEXT_LEG -> {
                ThreadItem.TextLeg(
                    id,
                    threadId,
                    turnId,
                    createdAt,
                    nullableString("text"),
                    getString("instructions"),
                    getInt("history_items"),
                )
            }

            NOTICE -> {
                ThreadItem.Notice(id, threadId, turnId, createdAt, NoticeKind.valueOf(getString("notice_kind")), getString("text"))
            }

            else -> {
                error("Unknown thread item type: $type")
            }
        }
    }

    private fun Boolean.int() = if (this) 1 else 0

    private companion object {
        const val CHANGE_BUFFER_CAPACITY = 64
        const val USER_MESSAGE = "USER_MESSAGE"
        const val ASSISTANT_MESSAGE = "ASSISTANT_MESSAGE"
        const val ACTION_CALL = "ACTION_CALL"
        const val NOTICE = "NOTICE"
        const val TEXT_LEG = "TEXT_LEG"
    }
}

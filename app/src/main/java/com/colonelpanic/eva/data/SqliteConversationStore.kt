package com.colonelpanic.eva.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.colonelpanic.eva.conversation.ConversationStore
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.Thread
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.Turn
import com.colonelpanic.eva.conversation.TurnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class SqliteConversationStore(
    private val helper: JournalDatabase,
) : ConversationStore {
    override val changes =
        MutableSharedFlow<String>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override suspend fun createThread(title: String): Thread {
        val created =
            withContext(Dispatchers.IO) {
                transaction { db ->
                    val now = maxOf(System.currentTimeMillis(), db.latestUpdatedAt() + 1)
                    Thread(UUID.randomUUID().toString(), title, now, now).also { thread ->
                        db.insertOrThrow("threads", null, thread.values())
                    }
                }
            }
        changes.tryEmit(created.id)
        return created
    }

    override suspend fun threads(): List<Thread> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query("threads", null, null, null, null, null, "updated_at DESC, rowid DESC")
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.thread()) } }
        }

    override suspend fun thread(id: String): Thread? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.query("threads", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.thread() else null
            }
        }

    override suspend fun retitle(
        id: String,
        title: String,
    ) {
        withContext(Dispatchers.IO) {
            transaction { db ->
                check(db.update("threads", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(id)) == 1) {
                    "Unknown thread: $id"
                }
                bump(db, id)
            }
        }
        changes.tryEmit(id)
    }

    override suspend fun items(
        threadId: String,
        limit: Int,
    ): List<ThreadItem> {
        require(limit >= 0)
        return withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query("items", null, "thread_id = ?", arrayOf(threadId), null, null, "rowid DESC", limit.toString())
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.item()) }.reversed() }
        }
    }

    override suspend fun turns(threadId: String): List<Turn> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query("turns", null, "thread_id = ?", arrayOf(threadId), null, null, "rowid ASC")
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.turn()) } }
        }

    override suspend fun openTurn(
        threadId: String,
        request: String,
        id: String,
    ): Turn {
        val turn = Turn(id, threadId, request, TurnStatus.OPEN, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            transaction { db ->
                db.insertOrThrow("turns", null, turn.values())
                bump(db, threadId)
            }
        }
        changes.tryEmit(threadId)
        return turn
    }

    override suspend fun closeTurn(
        turnId: String,
        status: TurnStatus,
    ) {
        val threadId =
            withContext(Dispatchers.IO) {
                transaction { db ->
                    val threadId = db.turnThreadId(turnId)
                    check(db.update("turns", ContentValues().apply { put("status", status.name) }, "id = ?", arrayOf(turnId)) == 1)
                    bump(db, threadId)
                    threadId
                }
            }
        changes.tryEmit(threadId)
    }

    override suspend fun reserveSideEffect(
        turnId: String,
        callId: String,
    ): Boolean {
        val result =
            withContext(Dispatchers.IO) {
                transaction { db ->
                    val current = db.turnReservation(turnId)
                    when {
                        current.callId == callId -> {
                            ReservationResult(true, current.threadId, false)
                        }

                        current.callId != null -> {
                            ReservationResult(false, current.threadId, false)
                        }

                        else -> {
                            val updated =
                                db.update(
                                    "turns",
                                    ContentValues().apply { put("side_effect_call_id", callId) },
                                    "id = ? AND side_effect_call_id IS NULL",
                                    arrayOf(turnId),
                                )
                            ReservationResult(updated == 1, current.threadId, updated == 1)
                        }
                    }
                }
            }
        if (result.changed) changes.tryEmit(result.threadId)
        return result.reserved
    }

    override suspend fun append(item: ThreadItem) {
        withContext(Dispatchers.IO) {
            transaction { db ->
                db.insertOrThrow("items", null, item.values())
                bump(db, item.threadId)
            }
        }
        changes.tryEmit(item.threadId)
    }

    override suspend fun recoverInterrupted(): List<Turn> {
        val recovered =
            withContext(Dispatchers.IO) {
                transaction { db ->
                    val open =
                        db.query("turns", null, "status = ?", arrayOf(TurnStatus.OPEN.name), null, null, "rowid ASC").use { cursor ->
                            buildList { while (cursor.moveToNext()) add(cursor.turn()) }
                        }
                    if (open.isNotEmpty()) {
                        db.update(
                            "turns",
                            ContentValues().apply { put("status", TurnStatus.INTERRUPTED.name) },
                            "status = ?",
                            arrayOf(TurnStatus.OPEN.name),
                        )
                    }
                    open.map { it.copy(status = TurnStatus.INTERRUPTED) }
                }
            }
        recovered.map { it.threadId }.distinct().forEach(changes::tryEmit)
        return recovered
    }

    private fun bump(
        db: SQLiteDatabase,
        threadId: String,
    ) {
        val current =
            db.query("threads", arrayOf("updated_at"), "id = ?", arrayOf(threadId), null, null, null).use { cursor ->
                check(cursor.moveToFirst()) { "Unknown thread: $threadId" }
                cursor.getLong(0)
            }
        val latest = db.latestUpdatedAt()
        val updatedAt = maxOf(System.currentTimeMillis(), current + 1, latest + 1)
        check(
            db.update("threads", ContentValues().apply { put("updated_at", updatedAt) }, "id = ?", arrayOf(threadId)) == 1,
        )
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun Thread.values() =
        ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", createdAtMillis)
            put("updated_at", updatedAtMillis)
        }

    private fun Turn.values() =
        ContentValues().apply {
            put("id", id)
            put("thread_id", threadId)
            put("request", request)
            put("status", status.name)
            put("created_at", createdAtMillis)
            put("side_effect_call_id", sideEffectCallId)
        }

    private fun ThreadItem.values() =
        ContentValues().apply {
            put("id", id)
            put("thread_id", threadId)
            put("turn_id", turnId)
            put("created_at", createdAtMillis)
            when (this@values) {
                is ThreadItem.UserMessage -> {
                    put("type", USER_MESSAGE)
                    put("text", text)
                    put("spoken", spoken)
                }

                is ThreadItem.AssistantMessage -> {
                    put("type", ASSISTANT_MESSAGE)
                    put("text", text)
                    put("spoken", spoken)
                    put("truncated", truncated)
                }

                is ThreadItem.ActionCall -> {
                    put("type", ACTION_CALL)
                    put("call_id", callId)
                    put("capability_id", capabilityId)
                    put("title", title)
                    put("arguments", Json.encodeToString(arguments))
                }

                is ThreadItem.Notice -> {
                    put("type", NOTICE)
                    put("notice_kind", kind.name)
                    put("text", text)
                }
            }
        }

    private fun Cursor.thread() =
        Thread(
            id = string("id"),
            title = string("title"),
            createdAtMillis = long("created_at"),
            updatedAtMillis = long("updated_at"),
        )

    private fun Cursor.turn() =
        Turn(
            id = string("id"),
            threadId = string("thread_id"),
            request = string("request"),
            status = TurnStatus.valueOf(string("status")),
            createdAtMillis = long("created_at"),
            sideEffectCallId = nullableString("side_effect_call_id"),
        )

    private fun Cursor.item(): ThreadItem {
        val id = string("id")
        val threadId = string("thread_id")
        val turnId = nullableString("turn_id")
        val createdAt = long("created_at")
        return when (string("type")) {
            USER_MESSAGE -> {
                ThreadItem.UserMessage(id, threadId, turnId, createdAt, string("text"), boolean("spoken"))
            }

            ASSISTANT_MESSAGE -> {
                ThreadItem.AssistantMessage(id, threadId, turnId, createdAt, string("text"), boolean("spoken"), boolean("truncated"))
            }

            ACTION_CALL -> {
                ThreadItem.ActionCall(
                    id,
                    threadId,
                    turnId,
                    createdAt,
                    string("call_id"),
                    string("capability_id"),
                    string("title"),
                    Json.decodeFromString(string("arguments")),
                )
            }

            NOTICE -> {
                ThreadItem.Notice(id, threadId, turnId, createdAt, NoticeKind.valueOf(string("notice_kind")), string("text"))
            }

            else -> {
                error("Unknown thread item type: ${string("type")}")
            }
        }
    }

    private fun SQLiteDatabase.turnThreadId(turnId: String): String =
        query("turns", arrayOf("thread_id"), "id = ?", arrayOf(turnId), null, null, null).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown turn: $turnId" }
            cursor.getString(0)
        }

    private fun SQLiteDatabase.turnReservation(turnId: String): TurnReservation =
        query("turns", arrayOf("thread_id", "side_effect_call_id"), "id = ?", arrayOf(turnId), null, null, null).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown turn: $turnId" }
            TurnReservation(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1))
        }

    private fun SQLiteDatabase.latestUpdatedAt() =
        rawQuery("SELECT MAX(updated_at) FROM threads", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))

    private fun Cursor.nullableString(column: String) = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.boolean(column: String) = getInt(getColumnIndexOrThrow(column)) != 0

    private data class TurnReservation(
        val threadId: String,
        val callId: String?,
    )

    private data class ReservationResult(
        val reserved: Boolean,
        val threadId: String,
        val changed: Boolean,
    )

    companion object {
        private const val CHANGE_BUFFER_CAPACITY = 64
        private const val USER_MESSAGE = "USER_MESSAGE"
        private const val ASSISTANT_MESSAGE = "ASSISTANT_MESSAGE"
        private const val ACTION_CALL = "ACTION_CALL"
        private const val NOTICE = "NOTICE"
    }
}

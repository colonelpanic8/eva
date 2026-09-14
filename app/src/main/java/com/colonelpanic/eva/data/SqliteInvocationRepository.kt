package com.colonelpanic.eva.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ClaimResult
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteInvocationRepository(
    private val helper: JournalDatabase,
) : InvocationRepository,
    AutoCloseable {
    constructor(
        context: Context,
        databaseName: String = DATABASE_NAME,
    ) : this(JournalDatabase(context, databaseName))

    override suspend fun recoverInterrupted() =
        withContext(Dispatchers.IO) {
            transaction { db ->
                db.update(
                    "invocations",
                    outcome(InvocationStatus.UNKNOWN, CapabilityDispatcher.UNKNOWN_MESSAGE),
                    "status = ?",
                    arrayOf(InvocationStatus.DISPATCHING.name),
                )
                db.update(
                    "invocations",
                    outcome(InvocationStatus.NOT_EXECUTED, "EVA closed before this action was sent. No app was opened."),
                    "status = ?",
                    arrayOf(InvocationStatus.CLAIMED.name),
                )
                Unit
            }
        }

    override suspend fun claim(record: InvocationRecord): ClaimResult =
        withContext(Dispatchers.IO) {
            transaction { db ->
                val previous = find(db, record.callId)
                if (previous != null) {
                    ClaimResult(previous, false)
                } else {
                    val values =
                        outcome(record.status, record.message).apply {
                            put("call_id", record.callId)
                            put("fingerprint", record.fingerprint)
                            put("request", record.request)
                            put("destination", record.destination)
                            put("created_at", record.createdAtMillis)
                            put("capability_id", record.capabilityId)
                            put("catalog_revision", record.catalogRevision)
                            put("title", record.title)
                            put("thread_id", record.threadId)
                            put("turn_id", record.turnId)
                        }
                    db.insertOrThrow("invocations", null, values)
                    ClaimResult(record, true)
                }
            }
        }

    override suspend fun transition(
        callId: String,
        expected: InvocationStatus,
        status: InvocationStatus,
        message: String,
    ): InvocationRecord =
        withContext(Dispatchers.IO) {
            transaction { db ->
                check(
                    db.update("invocations", outcome(status, message), "call_id = ? AND status = ?", arrayOf(callId, expected.name)) == 1,
                ) {
                    "The invocation changed before its state could be recorded."
                }
                checkNotNull(find(db, callId))
            }
        }

    override suspend fun history(): List<InvocationRecord> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.query("invocations", null, null, null, null, null, "rowid DESC", "100").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.record()) }.reversed()
            }
        }

    override suspend fun byCallIds(ids: Collection<String>): Map<String, InvocationRecord> =
        withContext(Dispatchers.IO) {
            buildMap {
                ids.distinct().chunked(MAX_QUERY_ARGUMENTS).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    helper.readableDatabase
                        .query("invocations", null, "call_id IN ($placeholders)", chunk.toTypedArray(), null, null, null)
                        .use { cursor ->
                            while (cursor.moveToNext()) {
                                val record = cursor.record()
                                put(record.callId, record)
                            }
                        }
                }
            }
        }

    override fun close() = helper.close()

    private fun find(
        db: SQLiteDatabase,
        callId: String,
    ): InvocationRecord? =
        db.query("invocations", null, "call_id = ?", arrayOf(callId), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.record() else null
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

    private fun outcome(
        status: InvocationStatus,
        message: String,
    ) = ContentValues().apply {
        put("status", status.name)
        put("message", message)
    }

    private fun Cursor.record() =
        InvocationRecord(
            callId = getString(getColumnIndexOrThrow("call_id")),
            fingerprint = getString(getColumnIndexOrThrow("fingerprint")),
            request = getString(getColumnIndexOrThrow("request")),
            destination = getColumnIndexOrThrow("destination").let { if (isNull(it)) null else getString(it) },
            status = InvocationStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
            message = getString(getColumnIndexOrThrow("message")),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            capabilityId = getString(getColumnIndexOrThrow("capability_id")),
            catalogRevision = getInt(getColumnIndexOrThrow("catalog_revision")),
            title = getColumnIndexOrThrow("title").let { if (isNull(it)) null else getString(it) },
            threadId = getColumnIndexOrThrow("thread_id").let { if (isNull(it)) null else getString(it) },
            turnId = getColumnIndexOrThrow("turn_id").let { if (isNull(it)) null else getString(it) },
        )

    companion object {
        const val DATABASE_NAME = "eva-actions.db"
        private const val MAX_QUERY_ARGUMENTS = 900
    }
}

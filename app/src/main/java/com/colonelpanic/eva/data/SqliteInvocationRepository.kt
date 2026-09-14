package com.colonelpanic.eva.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ClaimResult
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ReceiptProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SqliteInvocationRepository(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : InvocationRepository,
    AutoCloseable {
    private val helper = JournalDatabase(context.applicationContext, databaseName)

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
                            put(
                                "arguments_json",
                                record.arguments?.let {
                                    JsonObject(
                                        it.mapValues { entry ->
                                            JsonPrimitive(entry.value)
                                        },
                                    ).toString()
                                },
                            )
                            put("provenance_json", record.provenance?.toJson()?.toString())
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
            catalogRevision = getString(getColumnIndexOrThrow("catalog_revision")),
            title = getColumnIndexOrThrow("title").let { if (isNull(it)) null else getString(it) },
            arguments = nullableJson("arguments_json")?.mapValues { it.value.jsonPrimitive.content },
            provenance = nullableJson("provenance_json")?.let(ReceiptProvenance::fromJson),
        )

    private fun Cursor.nullableJson(column: String): JsonObject? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else Json.parseToJsonElement(getString(it)).jsonObject }

    private class JournalDatabase(
        context: Context,
        name: String,
    ) : SQLiteOpenHelper(context, name, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                    "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                    "capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, title TEXT, arguments_json TEXT, provenance_json TEXT)",
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            check(oldVersion in 1..3 && newVersion == 4)
            if (oldVersion < 3) {
                if (oldVersion == 1) db.execSQL("ALTER TABLE invocations ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE invocations RENAME TO invocations_legacy")
                onCreate(db)
                db.execSQL(
                    "INSERT INTO invocations (call_id, fingerprint, request, destination, status, message, created_at, " +
                        "capability_id, catalog_revision, title) SELECT call_id, fingerprint, request, destination, status, message, " +
                        "created_at, capability_id, CAST(catalog_revision AS TEXT), title FROM invocations_legacy ORDER BY rowid",
                )
                db.execSQL("DROP TABLE invocations_legacy")
            } else {
                db.execSQL("ALTER TABLE invocations ADD COLUMN arguments_json TEXT")
                db.execSQL("ALTER TABLE invocations ADD COLUMN provenance_json TEXT")
            }
        }
    }

    companion object {
        const val DATABASE_NAME = "eva-actions.db"
    }
}

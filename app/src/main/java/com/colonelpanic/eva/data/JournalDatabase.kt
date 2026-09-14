package com.colonelpanic.eva.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class JournalDatabase(
    context: Context,
    name: String,
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createInvocations(db)
        createConversationTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        check(oldVersion in 1 until VERSION && newVersion == VERSION)
        val columns =
            db.rawQuery("PRAGMA table_info(invocations)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
        db.execSQL("ALTER TABLE invocations RENAME TO invocations_legacy")
        createInvocations(db)
        val names =
            listOf(
                "call_id",
                "fingerprint",
                "request",
                "destination",
                "status",
                "message",
                "created_at",
                "capability_id",
                "catalog_revision",
                "title",
                "thread_id",
                "turn_id",
                "arguments_json",
                "provenance_json",
            )
        val values =
            names.map { name ->
                when {
                    name == "catalog_revision" -> "CAST(catalog_revision AS TEXT)"
                    name in columns -> name
                    else -> "NULL"
                }
            }
        db.execSQL(
            "INSERT INTO invocations (${names.joinToString()}) SELECT ${values.joinToString()} FROM invocations_legacy ORDER BY rowid",
        )
        db.execSQL("DROP TABLE invocations_legacy")
        createConversationTables(db)
    }

    private fun createInvocations(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, " +
                "title TEXT, thread_id TEXT, turn_id TEXT, arguments_json TEXT, provenance_json TEXT)",
        )
    }

    private fun createConversationTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, " +
                "status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, " +
                "created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, " +
                "call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS turns_thread_id ON turns(thread_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_thread_id ON items(thread_id)")
    }

    companion object {
        const val VERSION = 5
    }
}

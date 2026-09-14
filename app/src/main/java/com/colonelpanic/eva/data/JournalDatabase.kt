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
        check(oldVersion in 1..2 && newVersion == VERSION)
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE invocations ADD COLUMN title TEXT")
        }
        db.execSQL("ALTER TABLE invocations ADD COLUMN thread_id TEXT")
        db.execSQL("ALTER TABLE invocations ADD COLUMN turn_id TEXT")
        createConversationTables(db)
    }

    private fun createInvocations(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision INTEGER NOT NULL, " +
                "title TEXT, thread_id TEXT, turn_id TEXT)",
        )
    }

    private fun createConversationTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, " +
                "status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
        )
        db.execSQL(
            "CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, " +
                "created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, " +
                "call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT)",
        )
        db.execSQL("CREATE INDEX turns_thread_id ON turns(thread_id)")
        db.execSQL("CREATE INDEX items_thread_id ON items(thread_id)")
    }

    companion object {
        const val VERSION = 3
    }
}

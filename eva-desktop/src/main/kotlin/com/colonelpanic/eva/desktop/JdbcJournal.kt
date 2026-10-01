package com.colonelpanic.eva.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The action journal and conversation history in one SQLite file, with the phone's schema so the
 * same records mean the same thing on both. One connection serves every call, one at a time.
 */
class JdbcJournal(
    file: File,
) : AutoCloseable {
    private val connection: Connection
    private val mutex = Mutex()

    init {
        file.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        val version = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
        check(version == 0 || version in 7..VERSION) { "Unsupported journal version $version in $file" }
        if (version < VERSION) {
            connection.autoCommit = false
            try {
                val statements =
                    if (version == 0) {
                        SCHEMA
                    } else {
                        listOf(
                            "ALTER TABLE invocations ADD COLUMN initiator_json TEXT",
                            "ALTER TABLE items ADD COLUMN initiator_json TEXT",
                        )
                    }
                statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
                connection.createStatement().use { it.execute("PRAGMA user_version = $VERSION") }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** Runs [block] in one transaction; a failure rolls every statement in it back. */
    suspend fun <T> transaction(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                connection.autoCommit = false
                try {
                    block(connection).also { connection.commit() }
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block(connection) } }

    /** Waits for the statement in progress, so nothing is cut off mid-transaction. */
    override fun close() = runBlocking { mutex.withLock { connection.close() } }

    companion object {
        const val VERSION = 8

        private val SCHEMA =
            listOf(
                "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                    "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, " +
                    "title TEXT, thread_id TEXT, turn_id TEXT, arguments_json TEXT, provenance_json TEXT, data_json TEXT, initiator_json TEXT)",
                "CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE TABLE turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, " +
                    "status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
                "CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, " +
                    "created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, " +
                    "call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT, " +
                    "leg_id TEXT, instructions TEXT, history_items INTEGER, initiator_json TEXT)",
                "CREATE INDEX turns_thread_id ON turns(thread_id)",
                "CREATE INDEX items_thread_id ON items(thread_id)",
            )
    }
}

internal fun Connection.update(
    sql: String,
    vararg values: Any?,
): Int = prepareStatement(sql).use { it.bind(values).executeUpdate() }

internal fun <T> Connection.query(
    sql: String,
    vararg values: Any?,
    row: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(values).executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
    }

private fun PreparedStatement.bind(values: Array<out Any?>): PreparedStatement {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
    return this
}

internal fun ResultSet.nullableString(column: String): String? = getString(column)

internal fun ResultSet.flag(column: String): Boolean = getInt(column) != 0

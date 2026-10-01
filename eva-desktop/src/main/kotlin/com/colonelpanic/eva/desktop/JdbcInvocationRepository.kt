package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ClaimResult
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ReceiptProvenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet

/** The dispatcher's claim journal. Claims and transitions are atomic, as on the phone. */
class JdbcInvocationRepository(
    private val journal: JdbcJournal,
) : InvocationRepository {
    override suspend fun recoverInterrupted() {
        journal.transaction { db ->
            db.update(
                "UPDATE invocations SET status = ?, message = ?, data_json = NULL WHERE status = ?",
                InvocationStatus.UNKNOWN.name,
                CapabilityDispatcher.UNKNOWN_MESSAGE,
                InvocationStatus.DISPATCHING.name,
            )
            db.update(
                "UPDATE invocations SET status = ?, message = ?, data_json = NULL WHERE status = ?",
                InvocationStatus.NOT_EXECUTED.name,
                "EVA closed before this action was sent.",
                InvocationStatus.CLAIMED.name,
            )
        }
    }

    override suspend fun claim(record: InvocationRecord): ClaimResult =
        journal.transaction { db ->
            find(db, record.callId)?.let { return@transaction ClaimResult(it, false) }
            db.update(
                "INSERT INTO invocations (call_id, fingerprint, request, destination, status, message, created_at, capability_id, " +
                    "catalog_revision, title, thread_id, turn_id, arguments_json, provenance_json, data_json) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.callId,
                record.fingerprint,
                record.request,
                record.destination,
                record.status.name,
                record.message,
                record.createdAtMillis,
                record.capabilityId,
                record.catalogRevision,
                record.title,
                record.threadId,
                record.turnId,
                record.arguments?.let { arguments -> JsonObject(arguments.mapValues { JsonPrimitive(it.value) }).toString() },
                record.provenance?.toJson()?.toString(),
                record.data?.toString(),
            )
            ClaimResult(record, true)
        }

    override suspend fun transition(
        callId: String,
        expected: InvocationStatus,
        status: InvocationStatus,
        message: String,
        data: JsonObject?,
    ): InvocationRecord =
        journal.transaction { db ->
            check(
                db.update(
                    "UPDATE invocations SET status = ?, message = ?, data_json = ? WHERE call_id = ? AND status = ?",
                    status.name,
                    message,
                    data?.toString(),
                    callId,
                    expected.name,
                ) == 1,
            ) { "The invocation changed before its state could be recorded." }
            checkNotNull(find(db, callId))
        }

    override suspend fun history(): List<InvocationRecord> =
        journal.read { db -> db.query("SELECT * FROM invocations ORDER BY rowid DESC LIMIT 100") { it.record() }.reversed() }

    override suspend fun byCallIds(ids: Collection<String>): Map<String, InvocationRecord> =
        journal.read { db ->
            ids
                .distinct()
                .chunked(MAX_QUERY_ARGUMENTS)
                .flatMap { chunk ->
                    db.query("SELECT * FROM invocations WHERE call_id IN (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray()) {
                        it.record()
                    }
                }.associateBy { it.callId }
        }

    private fun find(
        db: Connection,
        callId: String,
    ): InvocationRecord? = db.query("SELECT * FROM invocations WHERE call_id = ?", callId) { it.record() }.firstOrNull()

    private fun ResultSet.record() =
        InvocationRecord(
            callId = getString("call_id"),
            fingerprint = getString("fingerprint"),
            request = getString("request"),
            destination = nullableString("destination"),
            status = InvocationStatus.valueOf(getString("status")),
            message = getString("message"),
            createdAtMillis = getLong("created_at"),
            capabilityId = getString("capability_id"),
            catalogRevision = getString("catalog_revision"),
            title = nullableString("title"),
            arguments = json("arguments_json")?.mapValues { it.value.jsonPrimitive.content },
            provenance = json("provenance_json")?.let(ReceiptProvenance::fromJson),
            threadId = nullableString("thread_id"),
            turnId = nullableString("turn_id"),
            data = json("data_json"),
        )

    private fun ResultSet.json(column: String): JsonObject? = nullableString(column)?.let { Json.parseToJsonElement(it).jsonObject }

    private companion object {
        const val MAX_QUERY_ARGUMENTS = 900
    }
}

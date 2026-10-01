package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import java.util.UUID

/** Provider lifecycle as trace events: identities and statuses, never transcript or reply text. */
internal fun traceProviderEvent(
    event: ProviderEvent,
    connection: String,
) {
    when (event) {
        is ProviderEvent.Connected -> {
            EvaTrace.info(
                "provider.connected",
                "connection" to connection,
                "session" to event.sessionId,
                "revision" to event.catalogRevision,
                "model" to listOfNotNull(event.model, event.backendModel).joinToString("+").ifEmpty { null },
            )
        }

        is ProviderEvent.ResponseStarted -> {
            EvaTrace.info(
                "response.started",
                "connection" to connection,
                "input" to event.inputId,
                "generation" to event.generationId,
                "announceOnly" to event.announceOnly.takeIf { it },
            )
        }

        is ProviderEvent.ResponseEnded -> {
            EvaTrace.info("response.ended", "connection" to connection, "input" to event.inputId, "status" to event.status)
        }

        is ProviderEvent.ToolCallReady -> {
            EvaTrace.info(
                "tool.proposed",
                "connection" to connection,
                "call" to event.call.callId,
                "capability" to event.capabilityId,
                "input" to event.call.inputId,
                "initiator" to
                    event.call.initiator
                        ?.kind
                        ?.wireName,
                "rejection" to event.rejection,
            )
        }

        is ProviderEvent.Transcript -> {
            EvaTrace.verbose(
                "transcript",
                "connection" to connection,
                "role" to event.role,
                "input" to event.inputId,
                "chars" to event.text.length,
            )
        }

        is ProviderEvent.AssistantText -> {
            EvaTrace.verbose("assistant.text", "connection" to connection, "input" to event.inputId, "chars" to event.text.length)
        }

        is ProviderEvent.Notice -> {
            EvaTrace.info("provider.notice", "connection" to connection, "message" to event.message)
        }

        is ProviderEvent.Failure -> {
            EvaTrace.info("provider.failure", "connection" to connection, "message" to event.message)
        }

        is ProviderEvent.ContextDelivery -> {
            EvaTrace.info(
                "context.delivery",
                "connection" to connection,
                "ids" to event.ids.joinToString(","),
                "delivered" to event.delivered,
            )
        }

        is ProviderEvent.AssistantSpeaking -> {
            EvaTrace.verbose("assistant.speaking", "connection" to connection, "speaking" to event.speaking)
        }

        ProviderEvent.UserSpeaking -> {
            EvaTrace.verbose("user.speaking", "connection" to connection)
        }

        is ProviderEvent.SpeechInputStarted -> {
            EvaTrace.verbose("speech.started", "connection" to connection, "item" to event.itemId)
        }

        is ProviderEvent.Account, ProviderEvent.Closed -> {}
    }
}

/** Records what a connection or leg was offered. Diagnostics must never break the session, so failures are only traced. */
internal suspend fun ConversationStore.recordOffered(
    threadId: String,
    turnId: String?,
    kind: SessionKind,
    legId: String?,
    model: String?,
    catalog: ProviderToolCatalog,
    nowMillis: Long,
) {
    EvaTrace.info(
        "session.catalog",
        "thread" to threadId,
        "turn" to turnId,
        "kind" to kind,
        "leg" to legId,
        "model" to model,
        "revision" to catalog.revision,
        "tools" to catalog.tools.size,
        "excluded" to catalog.excludedTools.size,
    )
    try {
        recordSessionCatalog(
            SessionCatalogRecord(
                UUID.randomUUID().toString(),
                threadId,
                turnId,
                nowMillis,
                kind,
                legId,
                model,
                catalog.revision,
                catalog.tools.map { OfferedTool(it.capabilityId, it.title) },
                catalog.excludedTools.map { it.capabilityId },
            ),
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        EvaTrace.info("session.catalog_unsaved", "thread" to threadId, "error" to error.javaClass.simpleName)
    }
}

/** Emits a trace event when a task appears, changes state, or leaves the task list. */
internal class TaskStateTrace {
    private var last = emptyMap<String, Triple<TaskKind, TaskState, Boolean>>()

    fun observe(snapshots: List<TaskSnapshot>) {
        val current = snapshots.associate { it.taskId to Triple(it.kind, it.state, it.looksStuck) }
        current.forEach { (id, value) ->
            if (last[id] != value) {
                val snapshot = snapshots.first { it.taskId == id }
                EvaTrace.info(
                    "task.state",
                    "task" to id,
                    "thread" to snapshot.threadId,
                    "kind" to value.first,
                    "state" to value.second,
                    "looksStuck" to value.third.takeIf { it },
                    "actions" to snapshot.actionCount,
                    "coverage" to snapshot.coverage,
                )
            }
        }
        (last.keys - current.keys).forEach { EvaTrace.info("task.ended", "task" to it) }
        last = current
    }
}

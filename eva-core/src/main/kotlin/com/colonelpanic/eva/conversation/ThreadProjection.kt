package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.displayMessage
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.HistoryItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the screen shows for a thread: one entry per turn, actions under it, notices between. Typed
 * input added to a running turn starts an entry of its own, holding what the turn did after it.
 */
fun projectEntries(
    turns: List<Turn>,
    items: List<ThreadItem>,
    receipts: Map<String, InvocationRecord>,
): List<ConversationEntry> {
    val byTurn = turns.associateBy { it.id }
    val order = linkedSetOf<String>()
    val requests = mutableMapOf<String, String>()
    val answers = mutableMapOf<String, MutableList<String>>()
    val built = mutableMapOf<String, ConversationEntry>()

    /** Later input within a turn, by entry id, with the turn it belongs to. */
    val followUps = mutableMapOf<String, Pair<String, String>>()

    /** Each turn's latest entry, which later answers and actions belong to. */
    val segments = mutableMapOf<String, String>()

    fun place(id: String) {
        order += id
    }

    for (item in items) {
        val turnId = item.turnId?.takeIf { it in byTurn }
        if (turnId != null) place(turnId)
        when (item) {
            is ThreadItem.Question -> {
                val question = item.evidence
                place(question.questionId)
                built[question.questionId] =
                    ConversationEntry(
                        question.questionId,
                        "",
                        "",
                        if (question.waiting) EntryStatus.PENDING else EntryStatus.ANSWER,
                        parentId = question.legId ?: turnId,
                        question = question,
                    )
            }

            is ThreadItem.UserMessage -> {
                when {
                    turnId == null -> {
                        place(item.id)
                        built[item.id] = ConversationEntry(item.id, item.text, "", EntryStatus.ANSWER)
                    }

                    turnId !in requests -> {
                        requests[turnId] = item.text
                    }

                    // A later spoken caption repeats speech the turn already shows.
                    !item.spoken -> {
                        place(item.id)
                        followUps[item.id] = turnId to item.text
                        segments[turnId] = item.id
                    }
                }
            }

            is ThreadItem.AssistantMessage -> {
                val text = item.text + if (item.truncated) "\n[Response was truncated]" else ""
                if (turnId != null) {
                    answers.getOrPut(segments[turnId] ?: turnId) { mutableListOf() } += text
                } else {
                    place(item.id)
                    built[item.id] = ConversationEntry(item.id, "", text, EntryStatus.ANSWER)
                }
            }

            is ThreadItem.ActionCall -> {
                place(item.callId)
                val receipt = receipts[item.callId]
                built[item.callId] =
                    ConversationEntry(
                        item.callId,
                        "",
                        receipt?.displayMessage() ?: "Preparing action…",
                        receipt?.status?.entryStatus() ?: EntryStatus.PENDING,
                        destination = receipt?.destination,
                        capabilityId = item.capabilityId,
                        actionTitle = item.title,
                        arguments = item.arguments,
                        result = receipt?.message,
                        parentId = item.legId ?: turnId?.let { segments[it] ?: it },
                        initiator = receipt?.initiator ?: item.initiator,
                        deviceSteps =
                            receipt
                                ?.data
                                ?.takeIf { item.capabilityId == CapabilityRegistry.DEVICE_TASK }
                                ?.let(
                                    ::deviceSteps,
                                ).orEmpty(),
                    )
            }

            is ThreadItem.TextLeg -> {
                place(item.id)
                val status = byTurn[turnId]?.status ?: TurnStatus.ANSWERED
                built[item.id] =
                    ConversationEntry(
                        item.id,
                        "",
                        item.task.orEmpty(),
                        if (status == TurnStatus.OPEN) EntryStatus.PENDING else EntryStatus.ANSWER,
                        parentId = turnId,
                        textLeg = TextLegDetails(item.task, item.instructions, item.historyItems, status),
                    )
            }

            is ThreadItem.Notice -> {
                place(item.id)
                built[item.id] = ConversationEntry(item.id, "", item.text, EntryStatus.SESSION)
            }
        }
    }
    for (turn in turns) place(turn.id)

    return order.mapNotNull { id ->
        built[id] ?: run {
            val (turnId, request) = followUps[id] ?: (id to (requests[id] ?: byTurn.getValue(id).request))
            val turn = byTurn.getValue(turnId)
            val answer = answers[id].orEmpty().joinToString("\n")
            when {
                turn.status == TurnStatus.ANSWERED || (segments[turnId] ?: turnId) != id -> {
                    if (request.isBlank() && answer.isBlank()) null else ConversationEntry(id, request, answer, EntryStatus.ANSWER)
                }

                turn.status == TurnStatus.OPEN -> {
                    ConversationEntry(id, request, answer.ifBlank { "Working on it…" }, EntryStatus.PENDING)
                }

                turn.status == TurnStatus.INTERRUPTED -> {
                    ConversationEntry(id, request, answer.ifBlank { "Interrupted." }, EntryStatus.ANSWER)
                }

                else -> {
                    ConversationEntry(id, request, "EVA could not finish this request.", EntryStatus.FAILED)
                }
            }
        }
    }
}

/** A device task receipt's steps; receipts from before steps were described fall back to the tool name. */
private fun deviceSteps(data: JsonObject): List<DeviceStep> =
    (data["steps"] as? JsonArray).orEmpty().mapNotNull { element ->
        val step = element as? JsonObject ?: return@mapNotNull null

        fun text(name: String) = (step[name] as? JsonPrimitive)?.contentOrNull
        val kind = text("kind") ?: return@mapNotNull null
        DeviceStep(
            (step["step"] as? JsonPrimitive)?.intOrNull ?: 0,
            kind,
            text("detail") ?: kind,
            text("result").orEmpty(),
            text("intent"),
            text("backend"),
        )
    }

/** Shows a running task's steps on its action before its receipt records them. */
fun List<ConversationEntry>.withLiveSteps(
    callId: String?,
    steps: List<DeviceStep>,
): List<ConversationEntry> = if (callId == null) this else map { if (it.id == callId) it.copy(deviceSteps = steps) else it }

private fun InvocationStatus.entryStatus() =
    when (this) {
        InvocationStatus.CLAIMED -> EntryStatus.PENDING
        InvocationStatus.DISPATCHING -> EntryStatus.DISPATCHING
        InvocationStatus.HANDED_OFF -> EntryStatus.HANDED_OFF
        InvocationStatus.COMPLETED -> EntryStatus.COMPLETED
        InvocationStatus.NOT_EXECUTED -> EntryStatus.NOT_EXECUTED
        InvocationStatus.FAILED -> EntryStatus.FAILED
        InvocationStatus.UNKNOWN -> EntryStatus.UNKNOWN
    }

/**
 * What a resumed or re-homed leg is told. Bounded from the end so a long thread costs a
 * fixed amount of context; what was dropped is said in one line.
 */
fun projectHistory(
    items: List<ThreadItem>,
    receipts: Map<String, InvocationRecord>,
    limit: Int = HISTORY_ITEM_LIMIT,
    /** The store's read was itself bounded, so the thread may hold more than [items]. */
    readBounded: Boolean = false,
    questionHistoryNote: String = Wording.bundled.message(Wording.BACKGROUND_QUESTION_HISTORY),
): List<HistoryItem> {
    val latestQuestions = items.filterIsInstance<ThreadItem.Question>().associateBy { it.evidence.questionId }
    val projected = items.filter { it !is ThreadItem.Question || latestQuestions[it.evidence.questionId] === it }
    val kept = projected.takeLast(limit)
    val dropped = projected.size - kept.size
    val head =
        when {
            readBounded && dropped > 0 -> listOf(HistoryItem.Note("At least $dropped earlier items in this conversation are not shown."))
            readBounded -> listOf(HistoryItem.Note("Earlier items in this conversation are not shown."))
            dropped > 0 -> listOf(HistoryItem.Note("$dropped earlier items in this conversation are not shown."))
            else -> emptyList()
        }
    return head +
        kept.map { item ->
            when (item) {
                is ThreadItem.Question -> {
                    HistoryItem.Question(item.evidence.data(), questionHistoryNote)
                }

                is ThreadItem.UserMessage -> {
                    HistoryItem.User(item.text)
                }

                is ThreadItem.AssistantMessage -> {
                    HistoryItem.Assistant(item.text + if (item.truncated) "\n[This response was cut off before it finished.]" else "")
                }

                is ThreadItem.ActionCall -> {
                    val receipt = receipts[item.callId]
                    HistoryItem.ActionEvidence(
                        item.title,
                        item.arguments,
                        receipt?.status?.name ?: InvocationStatus.UNKNOWN.name,
                        receipt?.message ?: "No outcome was recorded.",
                        provenance = receipt?.provenance,
                        data = receipt?.data,
                    )
                }

                is ThreadItem.Notice -> {
                    HistoryItem.Note(item.text)
                }

                is ThreadItem.TextLeg -> {
                    HistoryItem.Note(item.task?.let { "Continued in text: $it" } ?: "Continued after the call ended")
                }
            }
        }
}

const val HISTORY_ITEM_LIMIT = 40

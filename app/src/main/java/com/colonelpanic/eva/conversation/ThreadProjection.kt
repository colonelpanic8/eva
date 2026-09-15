package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.displayMessage
import com.colonelpanic.eva.providers.HistoryItem

/** What the screen shows for a thread: one entry per turn, actions under it, notices between. */
fun projectEntries(
    turns: List<Turn>,
    items: List<ThreadItem>,
    receipts: Map<String, InvocationRecord>,
): List<ConversationEntry> {
    val byTurn = turns.associateBy { it.id }
    val order = mutableListOf<String>()
    val requests = mutableMapOf<String, String>()
    val answers = mutableMapOf<String, MutableList<String>>()
    val built = mutableMapOf<String, ConversationEntry>()

    fun place(id: String) {
        if (id !in order) order += id
    }

    for (item in items) {
        val turnId = item.turnId?.takeIf { it in byTurn }
        if (turnId != null) place(turnId)
        when (item) {
            is ThreadItem.UserMessage -> {
                if (turnId != null) {
                    if (turnId !in requests) requests[turnId] = item.text
                } else {
                    place(item.id)
                    built[item.id] = ConversationEntry(item.id, item.text, "", EntryStatus.ANSWER)
                }
            }

            is ThreadItem.AssistantMessage -> {
                val text = item.text + if (item.truncated) "\n[Response was truncated]" else ""
                if (turnId != null) {
                    answers.getOrPut(turnId) { mutableListOf() } += text
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
                        parentId = turnId,
                    )
            }

            is ThreadItem.Notice -> {
                place(item.id)
                built[item.id] = ConversationEntry(item.id, "", item.text, EntryStatus.SESSION)
            }
        }
    }
    for (turn in turns) place(turn.id)

    return order.map { id ->
        built[id] ?: run {
            val turn = byTurn.getValue(id)
            val answer = answers[id].orEmpty().joinToString("\n")
            when {
                turn.status == TurnStatus.ANSWERED -> {
                    ConversationEntry(id, requests[id] ?: turn.request, answer.ifBlank { "Response completed." }, EntryStatus.ANSWER)
                }

                turn.status == TurnStatus.OPEN -> {
                    ConversationEntry(id, requests[id] ?: turn.request, answer.ifBlank { "Working on it…" }, EntryStatus.PENDING)
                }

                turn.status == TurnStatus.INTERRUPTED -> {
                    ConversationEntry(id, requests[id] ?: turn.request, answer.ifBlank { "Interrupted." }, EntryStatus.ANSWER)
                }

                else -> {
                    ConversationEntry(id, requests[id] ?: turn.request, "EVA could not finish this request.", EntryStatus.FAILED)
                }
            }
        }
    }
}

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
): List<HistoryItem> {
    val kept = items.takeLast(limit)
    val dropped = items.size - kept.size
    val head = if (dropped > 0) listOf(HistoryItem.Note("$dropped earlier items in this conversation are not shown.")) else emptyList()
    return head +
        kept.map { item ->
            when (item) {
                is ThreadItem.UserMessage -> {
                    HistoryItem.User(item.text)
                }

                is ThreadItem.AssistantMessage -> {
                    HistoryItem.Assistant(item.text)
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
            }
        }
}

const val HISTORY_ITEM_LIMIT = 40

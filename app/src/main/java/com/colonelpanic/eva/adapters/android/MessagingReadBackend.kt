package com.colonelpanic.eva.adapters.android

import android.Manifest
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * Exposes the conversations already on the phone: which threads exist, who is in them, and what was
 * recently said. A group has no phone number of its own, so listing threads is the only way a spoken
 * "text the climbing group" can reach one, and reading a thread is what makes a reply make sense.
 */
class MessagingReadBackend(
    private val host: AndroidIntentHost,
    private val store: MessagingStore,
    private val operation: Operation,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExecutionBackend {
    enum class Operation {
        CONVERSATIONS,
        MESSAGES,
    }

    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        if (!host.ensurePermission(Manifest.permission.READ_SMS)) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, PERMISSION_DENIED)
        }
        return when (operation) {
            Operation.CONVERSATIONS -> conversations(arguments)
            Operation.MESSAGES -> messages(arguments)
        }
    }

    private suspend fun conversations(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments["query"]?.trim()?.takeIf(String::isNotBlank)
        val limit = limit(arguments, ConversationSummaries.MAX_CONVERSATIONS)
        return ExecutionOutcome(
            InvocationStatus.COMPLETED,
            ConversationSummaries.describeConversations(query, store.conversations(query, limit), clock()),
        )
    }

    private suspend fun messages(arguments: Map<String, String>): ExecutionOutcome {
        val id =
            arguments["conversationId"]?.trim()?.toLongOrNull()
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, NEEDS_ID)
        val conversation =
            store.conversation(id)
                ?: return ExecutionOutcome(InvocationStatus.FAILED, MessageTargets.missing(id))
        val limit = limit(arguments, ConversationSummaries.MAX_MESSAGES)
        return ExecutionOutcome(
            InvocationStatus.COMPLETED,
            ConversationSummaries.describeMessages(conversation, store.messages(id, limit), clock()),
        )
    }

    private fun limit(
        arguments: Map<String, String>,
        maximum: Int,
    ) = arguments["limit"]?.trim()?.toIntOrNull()?.coerceIn(1, maximum) ?: maximum

    companion object {
        const val PERMISSION_DENIED =
            "Permission to read text messages was not granted. Allow it in EVA's app settings, " +
                "or name the person to text instead of a conversation."
        const val NEEDS_ID = "Give the conversationId returned by the conversation search."
    }
}

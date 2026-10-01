package com.colonelpanic.eva.adapters.android

import android.Manifest
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * Resolves a message capability's arguments to the numbers it will be addressed to. Targeting an
 * existing conversation is how a reply lands in that thread rather than in a new one, and it is the
 * only practical way to address a group whose members the user never dictated.
 */
class MessageTargets(
    private val host: AndroidIntentHost,
    private val store: MessagingStore,
) {
    sealed interface Target {
        data class Recipients(
            val numbers: List<String>,
            val conversation: Conversation?,
        ) : Target

        data class Refused(
            val outcome: ExecutionOutcome,
        ) : Target
    }

    suspend fun resolve(arguments: Map<String, String>): Target {
        val conversationId = arguments["conversationId"]?.trim()?.toLongOrNull()
        val recipient = arguments["recipient"]?.trim().orEmpty()
        return when {
            conversationId != null -> {
                fromConversation(conversationId)
            }

            recipient.isNotEmpty() -> {
                MessageRecipients.parse(recipient)?.let { Target.Recipients(it, null) }
                    ?: refuse(InvocationStatus.NOT_EXECUTED, MessageRecipients.INVALID)
            }

            else -> {
                refuse(InvocationStatus.NOT_EXECUTED, MessageRecipients.INVALID)
            }
        }
    }

    private suspend fun fromConversation(id: Long): Target {
        if (!host.ensurePermission(Manifest.permission.READ_SMS)) {
            return refuse(InvocationStatus.NOT_EXECUTED, READ_DENIED)
        }
        val conversation = store.conversation(id) ?: return refuse(InvocationStatus.FAILED, missing(id))
        val numbers = conversation.participants.map { MessageRecipients.normalize(it.number) }.filter(MessageRecipients::valid)
        if (numbers.isEmpty()) return refuse(InvocationStatus.FAILED, missing(id))
        if (numbers.size > MessageRecipients.MAX_RECIPIENTS) {
            return refuse(InvocationStatus.NOT_EXECUTED, TOO_MANY)
        }
        return Target.Recipients(numbers, conversation)
    }

    private fun refuse(
        status: InvocationStatus,
        message: String,
    ) = Target.Refused(ExecutionOutcome(status, message))

    companion object {
        const val READ_DENIED =
            "Permission to read text messages was not granted, so EVA cannot tell who is in that conversation. " +
                "Allow it in EVA's app settings, or give the phone numbers directly."
        const val TOO_MANY = "That conversation has more members than EVA will address at once."

        fun missing(id: Long) = "No conversation with ID $id is on this phone. Search conversations again for a current ID."
    }
}

package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal

/** Explicit app targeting never falls back to SMS. */
class MessagingBackend(
    private val operation: Operation,
    private val sms: ExecutionBackend,
    private val notifications: NotificationMessages,
) : ExecutionBackend {
    enum class Operation { SEARCH, READ, SEND }

    override fun receiptSource() = CapabilitySource("android.messaging", "Messaging")

    override fun dispatchRejection(proposal: ToolProposal): String? {
        val reference = proposal.arguments["conversationRef"] ?: return null
        return if (operation == Operation.SEND) notifications.replyRejection(reference, proposal.arguments["service"]) else null
    }

    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val service = arguments["service"]?.trim()?.takeIf { it.isNotEmpty() }
        val reference = arguments["conversationRef"]
        val targetApp = reference != null || (service != null && !service.equals("sms", true))
        if (!targetApp) return sms.unavailableReason()?.let { refused(it) } ?: sms.execute(arguments - "service")
        if (operation == Operation.SEARCH && arguments.containsKey("participants")) {
            return refused("Phone numbers find text conversations only. Search an app's conversations by name with query.")
        }
        if (arguments.containsKey("recipient") || arguments.containsKey("conversationId")) {
            return refused(
                "App messages require a conversationRef from conversation search. Phone numbers and SMS thread IDs cannot address app replies.",
            )
        }
        return when (operation) {
            Operation.SEARCH -> {
                notifications.search(service ?: "notifications", arguments["query"], arguments["limit"]?.toIntOrNull() ?: 5)
            }

            Operation.READ -> {
                reference?.let { notifications.read(it, service) }
                    ?: refused("Search this app's conversations and use its conversationRef.")
            }

            Operation.SEND -> {
                reference?.let { notifications.send(it, service, arguments["message"].orEmpty()) }
                    ?: refused(
                        "This app supports replies to active notifications only. Search for a conversationRef; starting a new chat requires opening the app.",
                    )
            }
        }
    }

    private fun refused(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
}

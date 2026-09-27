package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.android.ConversationSummaries
import com.colonelpanic.eva.adapters.android.MessageRecipients
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal

/**
 * Routes the shared messaging tools: SMS by default, a configured bridge when the service names
 * one or the reference belongs to one, and notification replies for any other app. Explicit app
 * targeting never falls back to SMS.
 */
class MessagingBackend(
    private val operation: Operation,
    private val sms: ExecutionBackend,
    private val notifications: NotificationMessages,
    private val bridges: BridgeMessaging? = null,
) : ExecutionBackend {
    enum class Operation { SEARCH, READ, SEND }

    override fun receiptSource() = CapabilitySource("android.messaging", "Messaging")

    override fun dispatchRejection(proposal: ToolProposal): String? {
        val reference = proposal.arguments["conversationRef"] ?: return null
        if (operation != Operation.SEND || bridges?.parseReference(reference) != null) return null
        return notifications.replyRejection(reference, proposal.arguments["service"])
    }

    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        execute(proposal.arguments, bridges?.idempotencyKey(proposal.callId, proposal.fingerprint()))

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome = execute(arguments, null)

    private suspend fun execute(
        arguments: Map<String, String>,
        idempotencyKey: String?,
    ): ExecutionOutcome {
        val service = arguments["service"]?.trim()?.takeIf { it.isNotEmpty() }
        val reference = arguments["conversationRef"]
        val referenced = reference?.let { bridges?.parseReference(it) }
        val bridge = bridges?.resolve(service)
        if (bridge != null || referenced != null) return bridged(service, bridge, referenced, arguments, idempotencyKey)
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

    private suspend fun bridged(
        service: String?,
        named: BridgeMessaging.Bridge?,
        referenced: Pair<String, String>?,
        arguments: Map<String, String>,
        idempotencyKey: String?,
    ): ExecutionOutcome {
        val bridges = checkNotNull(bridges)
        val bridge =
            when {
                referenced == null -> {
                    checkNotNull(named)
                }

                named != null && named.name == referenced.first -> {
                    named
                }

                service != null && !service.equals("notifications", true) -> {
                    return refused(
                        "That conversationRef belongs to ${referenced.first}, not $service. " +
                            "Search $service conversations for one of its own references.",
                    )
                }

                else -> {
                    bridges.bridge(referenced.first)
                        ?: return refused(
                            "That conversationRef belongs to a messaging service that is no longer configured. Search conversations again.",
                        )
                }
            }
        if (arguments.containsKey("conversationId")) {
            return refused(
                "SMS thread IDs cannot address ${bridge.label}. Use a conversationRef from a ${bridge.name} conversation search.",
            )
        }
        val limit = arguments["limit"]?.toIntOrNull()
        return when (operation) {
            Operation.SEARCH -> {
                val participants =
                    arguments["participants"]
                        ?.let { value ->
                            MessageRecipients.parse(value)
                                ?: return refused("Enter participants as phone numbers separated by commas; look names up first.")
                        }.orEmpty()
                bridges.search(bridge, arguments["query"], participants, limit ?: ConversationSummaries.MAX_CONVERSATIONS)
            }

            Operation.READ -> {
                referenced?.let { bridges.read(bridge, it.second, limit ?: ConversationSummaries.MAX_MESSAGES) }
                    ?: refused("Search ${bridge.name} conversations and use the conversationRef of the one to read.")
            }

            Operation.SEND -> {
                val target =
                    when {
                        referenced != null -> BridgeMessaging.Target.Conversation(referenced.second)
                        else -> bridgeRecipients(bridge, arguments["recipient"]) ?: return refused(recipientsHelp(bridge))
                    }
                bridges.send(
                    bridge,
                    target,
                    arguments["message"].orEmpty(),
                    checkNotNull(idempotencyKey) { "Bridge sends need an invocation" },
                )
            }
        }
    }

    private fun bridgeRecipients(
        bridge: BridgeMessaging.Bridge,
        recipient: String?,
    ): BridgeMessaging.Target.Recipients? {
        val numbers = recipient?.let(MessageRecipients::parse)?.map(MessageRecipients::normalize) ?: return null
        if (numbers.any { !it.startsWith("+") }) return null
        return BridgeMessaging.Target.Recipients(numbers)
    }

    private fun recipientsHelp(bridge: BridgeMessaging.Bridge) =
        "To message someone on ${bridge.label}, pass a conversationRef from a ${bridge.name} conversation search, " +
            "or their full international number starting with +, such as +14155550100, as recipient."

    private fun refused(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
}

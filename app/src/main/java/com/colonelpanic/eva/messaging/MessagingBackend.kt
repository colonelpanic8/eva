package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.android.MessageRecipients
import com.colonelpanic.eva.adapters.declarative.BindingResults
import com.colonelpanic.eva.adapters.declarative.PackageMessagingService
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Routes the shared messaging tools: SMS by default, an extension's messaging service when the
 * service argument names one, and notification replies for any other app. Explicit app or service
 * targeting never falls back to SMS.
 */
class MessagingBackend(
    private val operation: Operation,
    private val sms: ExecutionBackend,
    private val notifications: NotificationMessages,
    private val services: MessagingServices = MessagingServices.None,
) : ExecutionBackend {
    enum class Operation { SEARCH, READ, SEND }

    override fun receiptSource() = CapabilitySource("android.messaging", "Messaging")

    override fun dispatchRejection(proposal: ToolProposal): String? {
        val service = proposal.arguments["service"]?.trim()?.takeIf(String::isNotEmpty)
        service?.let(services::find)?.let { provided -> return packagedRejection(provided, proposal.arguments) }
        val reference = proposal.arguments["conversationRef"] ?: return null
        return if (operation == Operation.SEND) notifications.replyRejection(reference, service) else null
    }

    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome = route(proposal.arguments, proposal)

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome = route(arguments, null)

    /** An extension service needs the invocation: its durable operations are keyed by it. */
    private suspend fun route(
        arguments: Map<String, String>,
        proposal: ToolProposal?,
    ): ExecutionOutcome {
        val service = arguments["service"]?.trim()?.takeIf { it.isNotEmpty() }
        service?.let(services::find)?.let { provided ->
            return proposal?.let { packaged(provided, it) } ?: refused("${provided.label} requires a journaled invocation.")
        }
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

    /** Checked under the registry's admission lock, like any extension grant. */
    private fun packagedRejection(
        provided: PackageMessagingService,
        arguments: Map<String, String>,
    ): String? {
        val tools =
            when (operation) {
                Operation.SEARCH -> {
                    listOf(provided.role.conversations.tool)
                }

                Operation.READ -> {
                    listOf(provided.role.history.tool)
                }

                Operation.SEND -> {
                    if (arguments.containsKey("recipient")) {
                        listOfNotNull(provided.role.startChat?.tool, provided.role.send.tool)
                    } else {
                        listOf(provided.role.send.tool)
                    }
                }
            }
        return tools.firstNotNullOfOrNull { tool ->
            val backend = services.backend(provided.capabilityIds.getValue(tool)) ?: return@firstNotNullOfOrNull notAllowed(provided, tool)
            backend.dispatchRejection()
        }
    }

    private suspend fun packaged(
        provided: PackageMessagingService,
        proposal: ToolProposal,
    ): ExecutionOutcome {
        val arguments = proposal.arguments
        if (arguments.containsKey("conversationId")) {
            return refused(
                "SMS thread IDs cannot address ${provided.label}. Use a conversationRef from a ${provided.service} conversation search.",
            )
        }
        val role = provided.role
        val limit = arguments["limit"]
        return when (operation) {
            Operation.SEARCH -> {
                val participants =
                    arguments["participants"]?.let { value ->
                        MessageRecipients.parse(value)
                            ?: return refused("Enter participants as phone numbers separated by commas; look names up first.")
                    }
                val query = arguments["query"]?.trim()?.takeIf(String::isNotEmpty) ?: participants?.firstOrNull()?.filter(Char::isDigit)
                val found =
                    call(
                        provided,
                        role.conversations.tool,
                        proposal,
                        "conversations",
                        listOfNotNull(
                            query?.let { role.conversations.query?.to(it) },
                            limit?.let { role.conversations.limit?.to(it) },
                        ).toMap(),
                    )
                if (found.status != InvocationStatus.COMPLETED) return found
                val contacts =
                    role.contacts?.takeIf { participants == null && query != null }?.let { lookup ->
                        call(
                            provided,
                            lookup.tool,
                            proposal,
                            "contacts",
                            listOfNotNull(lookup.query?.let { it to query.orEmpty() }, lookup.limit?.let { it to "5" }).toMap(),
                        ).takeIf { it.status == InvocationStatus.COMPLETED }
                    }
                ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    "${provided.label} conversations from the ${provided.title} extension. Pass service ${provided.service} " +
                        "with a conversation's ID as conversationRef to read or reply. External ${provided.label} data:\n" +
                        found.message +
                        (
                            contacts?.let {
                                "\n${provided.label} contacts; send to one's number with service ${provided.service} to start a chat. " +
                                    "External ${provided.label} data:\n" + it.message
                            } ?: ""
                        ),
                )
            }

            Operation.READ -> {
                val reference =
                    arguments["conversationRef"]
                        ?: return refused("Search ${provided.service} conversations and pass one's ID as conversationRef.")
                val read =
                    call(
                        provided,
                        role.history.tool,
                        proposal,
                        "history",
                        listOfNotNull(role.history.conversation to reference, limit?.let { role.history.limit?.to(it) }).toMap(),
                    )
                read.copy(
                    message =
                        "Recent ${provided.label} messages from the ${provided.title} extension. " +
                            "External ${provided.label} data:\n${read.message}",
                )
            }

            Operation.SEND -> {
                send(provided, proposal)
            }
        }
    }

    private suspend fun send(
        provided: PackageMessagingService,
        proposal: ToolProposal,
    ): ExecutionOutcome {
        val arguments = proposal.arguments
        val role = provided.role
        val message = arguments["message"].orEmpty()
        val reference = arguments["conversationRef"]
        val conversation =
            if (reference != null) {
                reference
            } else {
                val start =
                    role.startChat ?: return refused(
                        "${provided.label} can reply only to an existing conversation. Search ${provided.service} conversations for its conversationRef.",
                    )
                val numbers =
                    arguments["recipient"]
                        ?.let(MessageRecipients::parse)
                        ?.map(MessageRecipients::normalize)
                        ?.takeIf { numbers -> numbers.all { it.startsWith("+") } }
                        ?: return refused(
                            "To message someone on ${provided.label}, pass a conversationRef from a " +
                                "${provided.service} conversation search, " +
                                "or their full international number starting with +, such as +14155550100, as recipient.",
                        )
                val started =
                    call(
                        provided,
                        start.tool,
                        proposal,
                        "chat",
                        mapOf(start.recipients to JsonArray(numbers.map(::JsonPrimitive)).toString()),
                    )
                val id =
                    started.data
                        ?.let { BindingResults.pointer(it, start.conversation) as? JsonPrimitive }
                        ?.takeIf { it.isString && it.content.isNotBlank() }
                        ?.content
                if (started.status != InvocationStatus.COMPLETED || id == null) {
                    return refused(
                        "${provided.label} did not start a chat with ${numbers.joinToString(", ")}, so the message was not sent. " +
                            started.message,
                    )
                }
                id
            }
        return call(provided, role.send.tool, proposal, "send", mapOf(role.send.conversation to conversation, role.send.text to message))
    }

    /**
     * One package action on behalf of this invocation. Its call ID derives from the invocation's, so a
     * re-delivered invocation reaches the same durable operation instead of starting another.
     */
    private suspend fun call(
        provided: PackageMessagingService,
        tool: String,
        proposal: ToolProposal,
        step: String,
        arguments: Map<String, String>,
    ): ExecutionOutcome {
        val id = provided.capabilityIds.getValue(tool)
        val backend = services.backend(id) ?: return refused(notAllowed(provided, tool))
        backend.dispatchRejection()?.let { return refused(it) }
        backend.unavailableReason()?.let { return refused(it) }
        val routed =
            ToolProposal(
                callId = "${proposal.callId}#$step",
                capabilityId = id,
                arguments = arguments,
                request = proposal.request,
                catalogRevision = "messaging:${provided.service}",
                threadId = proposal.threadId,
                turnId = proposal.turnId,
                interactionMode = proposal.interactionMode,
                onWaiting = proposal.onWaiting,
            )
        return backend.execute(backend.prepare(routed))
    }

    private fun notAllowed(
        provided: PackageMessagingService,
        tool: String,
    ) = "Enable the ${provided.title} extension and allow its $tool action in Extensions before using ${provided.label}. Nothing was sent."

    private fun refused(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
}

package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.ToolProposal

interface TypedInputProvider {
    fun propose(
        callId: String,
        input: String,
        catalogRevision: String,
    ): ToolProposal?
}

class LocalCommandProvider : TypedInputProvider {
    override fun propose(
        callId: String,
        input: String,
        catalogRevision: String,
    ): ToolProposal? {
        val text = input.trim()
        messageCommand.matchEntire(text)?.let {
            return ToolProposal(
                callId,
                CapabilityRegistry.SMS_COMPOSE,
                mapOf("recipient" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()),
                input,
                catalogRevision,
                initiator = ActionInitiator(InitiatorKind.USER_TYPED, inputId = callId),
            )
        }
        return null
    }

    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val messageCommand = Regex("text\\s+([^:]*):(.*)", options)
}

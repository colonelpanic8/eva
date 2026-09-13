package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ToolProposal

interface TypedInputProvider {
    fun propose(
        callId: String,
        input: String,
    ): ToolProposal?
}

class LocalCommandProvider : TypedInputProvider {
    override fun propose(
        callId: String,
        input: String,
    ): ToolProposal? {
        val text = input.trim()
        mapCommand.matchEntire(text)?.let {
            return ToolProposal(callId, CapabilityRegistry.MAP_SEARCH, mapOf("destination" to it.groupValues[1].trim()), input)
        }
        navigationCommand.matchEntire(text)?.let {
            return ToolProposal(callId, CapabilityRegistry.NAVIGATE, mapOf("destination" to it.groupValues[1].trim()), input)
        }
        messageCommand.matchEntire(text)?.let {
            return ToolProposal(
                callId,
                CapabilityRegistry.SMS_COMPOSE,
                mapOf("recipient" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()),
                input,
            )
        }
        return null
    }

    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val mapCommand = Regex("(?:map|open maps to)(?:\\s+(.*))?", options)
    private val navigationCommand = Regex("navigate to(?:\\s+(.*))?", options)
    private val messageCommand = Regex("text\\s+([^:]*):(.*)", options)
}

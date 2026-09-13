package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class CapabilityDefinition(
    val id: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val validateOperation: (Map<String, String>) -> String? = { null },
)

object BundledCapabilities {
    private val destinationSchema =
        schema(
            """
        {"type":"object","properties":{"destination":{"type":"string","minLength":1,"maxLength":500}},
        "required":["destination"],"additionalProperties":false}
    """,
        )
    private val messageSchema =
        schema(
            """
        {"type":"object","properties":{"recipient":{"type":"string","minLength":3,"maxLength":26},
        "message":{"type":"string","minLength":1,"maxLength":800}},
        "required":["recipient","message"],"additionalProperties":false}
    """,
        )
    private val phone = Regex("\\+?[0-9][0-9 ()-]{2,24}")
    val definitions =
        listOf(
            CapabilityDefinition(
                CapabilityRegistry.MAP_SEARCH,
                "Search maps",
                "Open a map search for a destination. Returns handoff to a map app, not arrival.",
                destinationSchema,
                ::validateDestination,
            ),
            CapabilityDefinition(
                CapabilityRegistry.NAVIGATE,
                "Driving navigation",
                "Request driving navigation to a destination. Returns handoff to a navigation app, not arrival.",
                destinationSchema,
                ::validateDestination,
            ),
            CapabilityDefinition(
                CapabilityRegistry.SMS_COMPOSE,
                "Prepare a text message",
                "Open a text message draft addressed to one explicit phone number. " +
                    "The user sends it in their messaging app. Does not send an SMS.",
                messageSchema,
            ) { args ->
                val recipient = args.getValue("recipient")
                val body = args.getValue("message")
                when {
                    !phone.matches(recipient) || recipient.count { it in '0'..'9' } !in 3..15 -> {
                        "Enter one valid phone number."
                    }

                    body.isBlank() || body.any { it.isISOControl() && it != '\n' && it != '\t' } -> {
                        "Enter a message without unsupported control characters."
                    }

                    else -> {
                        null
                    }
                }
            },
        )

    private fun validateDestination(args: Map<String, String>): String? =
        if (args.getValue("destination").isBlank() || args.getValue("destination").any { it.isISOControl() }) {
            "Enter a destination on one line."
        } else {
            null
        }

    private fun schema(value: String) = Json.parseToJsonElement(value).jsonObject
}

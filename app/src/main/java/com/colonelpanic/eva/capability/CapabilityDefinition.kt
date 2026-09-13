package com.colonelpanic.eva.capability

import com.colonelpanic.eva.adapters.android.NativeIntents
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
                ::validateMessage,
            ),
            CapabilityDefinition(
                CapabilityRegistry.SET_ALARM,
                "Set an alarm",
                "Set a clock alarm at a local wall-clock time on a 24-hour scale. " +
                    "Use for a specific time of day, not for a countdown.",
                schema(
                    """
                {"type":"object","properties":{
                "hour":{"type":"integer","minimum":0,"maximum":23,"description":"Local hour on a 24-hour clock"},
                "minute":{"type":"integer","minimum":0,"maximum":59},
                "label":{"type":"string","minLength":1,"maxLength":120}},
                "required":["hour","minute"],"additionalProperties":false}
            """,
                ),
            ),
            CapabilityDefinition(
                CapabilityRegistry.SET_TIMER,
                "Set a timer",
                "Start a countdown timer for a number of seconds. Use for durations such as ten minutes.",
                schema(
                    """
                {"type":"object","properties":{
                "seconds":{"type":"integer","minimum":1,"maximum":86400},
                "label":{"type":"string","minLength":1,"maxLength":120}},
                "required":["seconds"],"additionalProperties":false}
            """,
                ),
            ),
            CapabilityDefinition(
                CapabilityRegistry.DIAL,
                "Open the dialer",
                "Open the phone dialer with a number filled in. The user places the call. Does not dial automatically.",
                schema(
                    """
                {"type":"object","properties":{"number":{"type":"string","minLength":3,"maxLength":26}},
                "required":["number"],"additionalProperties":false}
            """,
                ),
            ) { args -> if (phone.matches(args.getValue("number"))) null else "Enter one valid phone number." },
            CapabilityDefinition(
                CapabilityRegistry.WEB_SEARCH,
                "Search the web",
                "Open a web search for a query in the user's browser. " +
                    "Prefer answering from your own knowledge; use this when the user asks to search.",
                schema(
                    """
                {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":500}},
                "required":["query"],"additionalProperties":false}
            """,
                ),
            ) { args -> if (args.getValue("query").isBlank()) "Enter something to search for." else null },
            CapabilityDefinition(
                CapabilityRegistry.OPEN_URL,
                "Open a web page",
                "Open an http or https web address in the user's browser.",
                schema(
                    """
                {"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2000}},
                "required":["url"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val url = args.getValue("url")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    null
                } else {
                    "Enter an http or https web address."
                }
            },
            CapabilityDefinition(
                CapabilityRegistry.EMAIL_COMPOSE,
                "Prepare an email",
                "Open an email draft addressed to one recipient. The user sends it. Does not send mail.",
                schema(
                    """
                {"type":"object","properties":{
                "recipient":{"type":"string","minLength":3,"maxLength":320},
                "subject":{"type":"string","minLength":1,"maxLength":300},
                "body":{"type":"string","minLength":1,"maxLength":4000}},
                "required":["recipient"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val recipient = args.getValue("recipient")
                if (recipient.count { it == '@' } == 1 && !recipient.first().isWhitespace() && !recipient.contains(' ')) {
                    null
                } else {
                    "Enter one valid email address."
                }
            },
            CapabilityDefinition(
                CapabilityRegistry.CALENDAR_EVENT,
                "Create a calendar event",
                "Open a prefilled new calendar event. The user saves it. " +
                    "Supply startEpochMillis in Unix milliseconds when a time is known.",
                schema(
                    """
                {"type":"object","properties":{
                "title":{"type":"string","minLength":1,"maxLength":300},
                "startEpochMillis":{"type":"integer","minimum":0,"maximum":4102444800000},
                "durationMinutes":{"type":"integer","minimum":1,"maximum":10080},
                "location":{"type":"string","minLength":1,"maxLength":300},
                "description":{"type":"string","minLength":1,"maxLength":2000}},
                "required":["title"],"additionalProperties":false}
            """,
                ),
            ),
            CapabilityDefinition(
                CapabilityRegistry.OPEN_APP,
                "Open an app",
                "Launch an installed app by its visible name, such as Settings or Chrome.",
                schema(
                    """
                {"type":"object","properties":{"app":{"type":"string","minLength":1,"maxLength":100}},
                "required":["app"],"additionalProperties":false}
            """,
                ),
            ) { args -> if (args.getValue("app").isBlank()) "Name the app to open." else null },
            CapabilityDefinition(
                CapabilityRegistry.OPEN_SETTINGS,
                "Open a settings screen",
                "Open one of the device's settings screens. EVA cannot change a setting directly.",
                schema(
                    """
                {"type":"object","properties":{"screen":{"type":"string","enum":${NativeIntents.settingsScreenNames.quoted()}}},
                "required":["screen"],"additionalProperties":false}
            """,
                ),
            ),
        )

    private fun List<String>.quoted() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun validateMessage(args: Map<String, String>): String? {
        val recipient = args.getValue("recipient")
        val body = args.getValue("message")
        return when {
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
    }

    private fun validateDestination(args: Map<String, String>): String? =
        if (args.getValue("destination").isBlank() || args.getValue("destination").any { it.isISOControl() }) {
            "Enter a destination on one line."
        } else {
            null
        }

    private fun schema(value: String) = Json.parseToJsonElement(value).jsonObject
}

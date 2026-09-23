package com.colonelpanic.eva.capability

import com.colonelpanic.eva.adapters.android.ContactField
import com.colonelpanic.eva.adapters.android.ConversationSummaries
import com.colonelpanic.eva.adapters.android.MediaCommand
import com.colonelpanic.eva.adapters.android.MessageRecipients
import com.colonelpanic.eva.adapters.android.VolumeAction
import com.colonelpanic.eva.conversation.prompt.Wording
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class CapabilityDefinition(
    val id: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    /** Claims observation without mutation; this does not authorize execution or tool chaining. */
    val readOnly: Boolean = false,
    val source: CapabilitySource? = null,
    val validateOperation: (Map<String, String>) -> String? = { null },
)

object BundledCapabilities {
    private val messageSchema =
        schema(
            """
        {"type":"object","properties":{
        "recipient":{"type":"string","minLength":3,"maxLength":300},
        "conversationId":{"type":"integer","minimum":1},
        "message":{"type":"string","minLength":1,"maxLength":800}},
        "required":["message"],"additionalProperties":false}
    """,
        )
    private val appMessageFields =
        schema(
            """{"service":{"type":"string","minLength":1,"maxLength":200},
        "conversationRef":{"type":"string","minLength":1,"maxLength":100}}""",
        )
    private val sendSchema =
        JsonObject(
            messageSchema + (
                "properties" to
                    JsonObject((messageSchema.getValue("properties") as JsonObject) + appMessageFields)
            ),
        )
    private val phone = MessageRecipients.phone

    val definitions =
        listOf(
            tool(
                CapabilityRegistry.SMS_COMPOSE,
                "Prepare a text message",
                messageSchema,
                validateOperation = ::validateMessage,
            ),
            tool(
                CapabilityRegistry.SMS_SEND,
                "Send a message",
                sendSchema,
                validateOperation = { args ->
                    if ("conversationRef" in args) {
                        when {
                            "recipient" in args || "conversationId" in args -> "Use exactly one message target."
                            args.getValue("message").isBlank() -> "Enter a nonempty message."
                            else -> null
                        }
                    } else {
                        validateMessage(args)
                    }
                },
            ),
            tool(
                CapabilityRegistry.DIAL,
                "Open the dialer",
                schema(
                    """
                {"type":"object","properties":{"number":{"type":"string","minLength":3,"maxLength":26}},
                "required":["number"],"additionalProperties":false}
            """,
                ),
            ) { args -> if (phone.matches(args.getValue("number"))) null else "Enter one valid phone number." },
            tool(
                CapabilityRegistry.OPEN_APP,
                "Open an app",
                schema(
                    """
                {"type":"object","properties":{"app":{"type":"string","minLength":1,"maxLength":100}},
                "required":["app"],"additionalProperties":false}
            """,
                ),
            ) { args -> if (args.getValue("app").isBlank()) "Name the app to open." else null },
            tool(
                CapabilityRegistry.CONTACTS_SEARCH,
                "Search contacts",
                schema(
                    """
                {"type":"object","properties":{
                "query":{"type":"string","minLength":1,"maxLength":100},
                "field":{"type":"string","enum":${ContactField.arguments.quoted()}}},
                "required":["query"],"additionalProperties":false}
            """,
                ),
                readOnly = true,
            ) { args ->
                val query = args.getValue("query")
                if (query.isBlank() || query.any { it.isISOControl() }) "Enter part of a name." else null
            },
            tool(
                CapabilityRegistry.CONVERSATIONS_SEARCH,
                "Find messaging conversations",
                schema(
                    """{"type":"object","properties":{
                    "service":{"type":"string","minLength":1,"maxLength":200},
                    "query":{"type":"string","minLength":1,"maxLength":100},
                    "limit":{"type":"integer","minimum":1,"maximum":${ConversationSummaries.MAX_CONVERSATIONS}}},
                    "required":[],"additionalProperties":false}""",
                ),
                readOnly = true,
                validateOperation = { args ->
                    if (args["query"].orEmpty().any(Char::isISOControl)) "Enter part of a name or number." else null
                },
            ),
            tool(
                CapabilityRegistry.CONVERSATION_READ,
                "Read a messaging conversation",
                schema(
                    """{"type":"object","properties":{
                    "service":{"type":"string","minLength":1,"maxLength":200},
                    "conversationRef":{"type":"string","minLength":1,"maxLength":100},
                    "conversationId":{"type":"integer","minimum":1},
                    "limit":{"type":"integer","minimum":1,"maximum":${ConversationSummaries.MAX_MESSAGES}}},
                    "required":[],"additionalProperties":false}""",
                ),
                readOnly = true,
                validateOperation = { args ->
                    if (("conversationId" in args) ==
                        ("conversationRef" in args)
                    ) {
                        "Use exactly one conversationId or conversationRef."
                    } else {
                        null
                    }
                },
            ),
            tool(
                CapabilityRegistry.MEDIA_CONTROL,
                "Control what is playing",
                schema(
                    """
                {"type":"object","properties":{
                "action":{"type":"string","enum":${MediaCommand.arguments.quoted()}}},
                "required":["action"],"additionalProperties":false}
            """,
                ),
            ),
            tool(
                CapabilityRegistry.MEDIA_NOW_PLAYING,
                "Read what is playing",
                schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
            ),
            tool(
                CapabilityRegistry.MEDIA_PLAY,
                "Play something",
                schema(
                    """
                {"type":"object","properties":{
                "query":{"type":"string","minLength":1,"maxLength":300}},
                "required":["query"],"additionalProperties":false}
            """,
                ),
                readOnly = true,
            ) { args ->
                if (args.getValue("query").isBlank() || args.getValue("query").any(Char::isISOControl)) {
                    "Say what to play on one line."
                } else {
                    null
                }
            },
            tool(
                CapabilityRegistry.MEDIA_VOLUME,
                "Change the media volume",
                schema(
                    """
                {"type":"object","properties":{
                "action":{"type":"string","enum":${VolumeAction.arguments.quoted()}},
                "percent":{"type":"integer","minimum":0,"maximum":100}},
                "required":["action"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                if (args["action"] == VolumeAction.SET.argument && args["percent"]?.toIntOrNull() == null) {
                    "Give percent between 0 and 100 to set the volume."
                } else {
                    null
                }
            },
            tool(
                CapabilityRegistry.DEVICE_STATE_GET,
                "Read device state",
                schema(
                    """
                {"type":"object","properties":{"category":{"type":"string","enum":[
                "battery","storage","notifications","apps","mobile_data","uncategorized"]}},
                "required":["category"],"additionalProperties":false}
            """,
                ),
                readOnly = true,
            ),
            tool(
                CapabilityRegistry.DEVICE_STATE_SET,
                "Change a device setting",
                schema(
                    """
                {"type":"object","properties":{
                "key":{"type":"string","minLength":3,"maxLength":200},
                "value":{"type":"string","minLength":1,"maxLength":200}},
                "required":["key","value"],"additionalProperties":false}
            """,
                ),
            ) { args -> validateDeviceStateSet(args) },
            tool(
                CapabilityRegistry.DEVICE_STATE_METADATA,
                "Find writable device settings",
                schema(
                    """
                {"type":"object","properties":{
                "search":{"type":"string","minLength":1,"maxLength":100}},
                "required":["search"],"additionalProperties":false}
            """,
                ),
                readOnly = true,
            ) { args ->
                val search = args.getValue("search")
                if (search.isBlank() || search.any(Char::isISOControl)) "Enter a setting name or key to search for." else null
            },
            tool(
                CapabilityRegistry.UI_OBSERVE,
                "Look at the screen",
                schema(
                    """
                {"type":"object","properties":{},"required":[],"additionalProperties":false}
            """,
                ),
                readOnly = true,
            ),
            tool(
                CapabilityRegistry.UI_TAP,
                "Tap an element on screen",
                schema(
                    """
                {"type":"object","properties":{
                "observationRef":{"type":"string","minLength":1,"maxLength":64},
                "node":{"type":"integer","minimum":0,"maximum":199}},
                "required":["observationRef","node"],"additionalProperties":false}
            """,
                ),
            ),
            tool(
                CapabilityRegistry.UI_SET_TEXT,
                "Replace text in a field on screen",
                schema(
                    """
                {"type":"object","properties":{
                "observationRef":{"type":"string","minLength":1,"maxLength":64},
                "node":{"type":"integer","minimum":0,"maximum":199},
                "text":{"type":"string","maxLength":2000}},
                "required":["observationRef","node","text"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val text = args.getValue("text")
                if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) {
                    "Enter text without unsupported control characters."
                } else {
                    null
                }
            },
        )

    /** A tool EVA defines itself; what it says to the model comes from [Wording]. */
    private fun tool(
        id: String,
        title: String,
        inputSchema: JsonObject,
        readOnly: Boolean = false,
        validateOperation: (Map<String, String>) -> String? = { null },
    ): CapabilityDefinition {
        val text = Wording.bundled.tools[id]
        return CapabilityDefinition(
            id,
            title,
            text?.description.orEmpty(),
            Wording.withParameters(inputSchema, text?.parameters.orEmpty()),
            readOnly,
            validateOperation = validateOperation,
        )
    }

    private fun List<String>.quoted() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun validateMessage(args: Map<String, String>): String? {
        val recipient = args["recipient"]?.trim().orEmpty()
        val conversation = args["conversationId"]?.trim().orEmpty()
        val body = args.getValue("message")
        return when {
            recipient.isEmpty() == conversation.isEmpty() -> {
                "Address the message with either recipient or conversationId, not both."
            }

            recipient.isNotEmpty() && MessageRecipients.parse(recipient) == null -> {
                MessageRecipients.INVALID
            }

            conversation.isNotEmpty() && (conversation.toLongOrNull() ?: 0L) <= 0L -> {
                "Use a conversationId returned by the conversation search."
            }

            body.isBlank() || body.any { it.isISOControl() && it != '\n' && it != '\t' } -> {
                "Enter a message without unsupported control characters."
            }

            else -> {
                null
            }
        }
    }

    private fun validateDeviceStateSet(args: Map<String, String>): String? {
        val key = args.getValue("key")
        val value = args.getValue("value")
        return when {
            !DEVICE_STATE_KEY.matches(key) -> "Use an exact setting key returned by the writable-settings search."
            value.isBlank() || value.any(Char::isISOControl) -> "Enter a bounded setting value without control characters."
            else -> null
        }
    }

    private fun schema(value: String) = Json.parseToJsonElement(value).jsonObject

    private val DEVICE_STATE_KEY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
}

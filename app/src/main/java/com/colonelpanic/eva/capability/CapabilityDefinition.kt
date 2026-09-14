package com.colonelpanic.eva.capability

import com.colonelpanic.eva.adapters.android.ContactField
import com.colonelpanic.eva.adapters.android.ConversationSummaries
import com.colonelpanic.eva.adapters.android.MessageRecipients
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
        {"type":"object","properties":{
        "recipient":{"type":"string","minLength":3,"maxLength":300,
        "description":"One phone number, or several separated by commas to start a group message"},
        "conversationId":{"type":"integer","minimum":1,
        "description":"An existing conversation from the conversation search; use this instead of recipient to reach a group"},
        "message":{"type":"string","minLength":1,"maxLength":800}},
        "required":["message"],"additionalProperties":false}
    """,
        )
    private val phone = MessageRecipients.phone

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
                "Open a text message draft addressed to explicit phone numbers or to an existing conversation. " +
                    "The user sends it in their messaging app, so use this only when they ask to review the text first " +
                    "or when sending directly is unavailable. Does not send an SMS.",
                messageSchema,
                ::validateMessage,
            ),
            CapabilityDefinition(
                CapabilityRegistry.SMS_SEND,
                "Send a text message",
                "Send a text message without opening another app, to one phone number, to several at once, " +
                    "or to an existing conversation given its conversationId. " +
                    "Use this for hands-free requests to text someone. " +
                    "When the user names a person, find the number with the contacts search first and use the best match; " +
                    "when they mean a group or an ongoing thread, find it with the conversation search and send to its " +
                    "conversationId, which keeps the message in that one conversation instead of starting separate threads. " +
                    "Sending cannot be undone, so confirm the wording first when the user has not dictated it.",
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
                CapabilityRegistry.CONTACTS_SEARCH,
                "Search contacts",
                "Find phone numbers in the user's contacts by name. Use this when the user names a person " +
                    "to text or call, then pass the returned number to the send, message, or dialer action. " +
                    "Returns matches only; it opens nothing. Names match approximately, so pass the whole name as heard: " +
                    "a misspelled or misheard name still finds the contact, and a full name ranks the right person above " +
                    "others who share one part of it. Choose what to match with field: name searches the whole displayed " +
                    "name and nicknames, given searches first names, family searches last names. Search again with " +
                    "another spelling or field only when the result reports no match for every part of the name. " +
                    "Results are ranked, with the people the user has been in touch with breaking ties, so judge which " +
                    "match the user most plausibly meant and act on it; ask which person only when the result says " +
                    "matches are equally plausible. Use a contact's first number, which is the one the user last used " +
                    "or else their mobile; use another only when the user asks for it.",
                schema(
                    """
                {"type":"object","properties":{
                "query":{"type":"string","minLength":1,"maxLength":100,"description":"All or part of a person's name"},
                "field":{"type":"string","enum":${ContactField.arguments.quoted()},
                "description":"Which stored name to match; defaults to the whole displayed name"}},
                "required":["query"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val query = args.getValue("query")
                if (query.isBlank() || query.any { it.isISOControl() }) "Enter part of a name." else null
            },
            CapabilityDefinition(
                CapabilityRegistry.CONVERSATIONS_SEARCH,
                "Find text conversations",
                "List the text conversations already on this phone, newest first, naming who is in each one and " +
                    "its conversationId. Use this whenever the user means an existing thread or a group chat: a group " +
                    "has no phone number of its own, so its conversationId is the only way to text it. An optional " +
                    "query keeps only conversations with a matching participant name or number. " +
                    "Returns matches only; it opens and sends nothing.",
                schema(
                    """
                {"type":"object","properties":{
                "query":{"type":"string","minLength":1,"maxLength":100,
                "description":"Part of a participant's name or number; omit for the most recent conversations"},
                "limit":{"type":"integer","minimum":1,"maximum":${ConversationSummaries.MAX_CONVERSATIONS}}},
                "required":[],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val query = args["query"].orEmpty()
                if (query.any(Char::isISOControl)) "Enter part of a name or number." else null
            },
            CapabilityDefinition(
                CapabilityRegistry.CONVERSATION_READ,
                "Read a conversation",
                "Read the most recent messages in one conversation, oldest last, with who sent each one and how long " +
                    "ago. Use it to catch up on a thread, to check what a reply should answer, or to confirm a " +
                    "conversation is the one the user meant. Find the conversationId with the conversation search first.",
                schema(
                    """
                {"type":"object","properties":{
                "conversationId":{"type":"integer","minimum":1},
                "limit":{"type":"integer","minimum":1,"maximum":${ConversationSummaries.MAX_MESSAGES},
                "description":"How many recent messages to read; defaults to the maximum"}},
                "required":["conversationId"],"additionalProperties":false}
            """,
                ),
            ),
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
            CapabilityDefinition(
                CapabilityRegistry.DEVICE_STATE_GET,
                "Read device state",
                "Read one category of Android device state through Settings AppFunctions. " +
                    "Returns current values without opening Settings.",
                schema(
                    """
                {"type":"object","properties":{"category":{"type":"string","enum":[
                "battery","storage","notifications","apps","mobile_data","uncategorized"]}},
                "required":["category"],"additionalProperties":false}
            """,
                ),
            ),
            CapabilityDefinition(
                CapabilityRegistry.DEVICE_STATE_SET,
                "Change a device setting",
                "Change one writable Android setting through Settings AppFunctions. " +
                    "Find its exact key and accepted values with the writable-settings search first.",
                schema(
                    """
                {"type":"object","properties":{
                "key":{"type":"string","minLength":3,"maxLength":200},
                "value":{"type":"string","minLength":1,"maxLength":200}},
                "required":["key","value"],"additionalProperties":false}
            """,
                ),
            ) { args -> validateDeviceStateSet(args) },
            CapabilityDefinition(
                CapabilityRegistry.DEVICE_STATE_METADATA,
                "Find writable device settings",
                "Search writable Android settings exposed by Settings AppFunctions. " +
                    "Returns exact keys, purposes, and accepted-value descriptions for a later change.",
                schema(
                    """
                {"type":"object","properties":{
                "search":{"type":"string","minLength":1,"maxLength":100}},
                "required":["search"],"additionalProperties":false}
            """,
                ),
            ) { args ->
                val search = args.getValue("search")
                if (search.isBlank() || search.any(Char::isISOControl)) "Enter a setting name or key to search for." else null
            },
        )

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

    private fun validateDestination(args: Map<String, String>): String? =
        if (args.getValue("destination").isBlank() || args.getValue("destination").any { it.isISOControl() }) {
            "Enter a destination on one line."
        } else {
            null
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

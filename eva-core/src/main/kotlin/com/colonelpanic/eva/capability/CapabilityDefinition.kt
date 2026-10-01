package com.colonelpanic.eva.capability

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
    /** How the source's tools fit together, told to the model while any of them is offered. */
    val guidance: String? = null,
    val validateOperation: (Map<String, String>) -> String? = { null },
    /**
     * A write to EVA's own records made without being asked: it does not serve the request, and
     * failing does not leave the phone's state in doubt.
     */
    val bookkeeping: Boolean = false,
    /** The declared default; the user's configuration may override it per action. */
    val endsVoiceCall: CallEnding = CallEnding.NEVER,
)

/** A tool EVA defines itself; what it says to the model comes from [Wording]. */
fun tool(
    id: String,
    title: String,
    inputSchema: JsonObject,
    readOnly: Boolean = false,
    bookkeeping: Boolean = false,
    endsVoiceCall: CallEnding = CallEnding.NEVER,
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
        bookkeeping = bookkeeping,
        endsVoiceCall = endsVoiceCall,
    )
}

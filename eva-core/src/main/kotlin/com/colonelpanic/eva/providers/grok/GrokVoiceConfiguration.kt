package com.colonelpanic.eva.providers.grok

import kotlinx.serialization.Serializable

@Serializable
data class GrokVoiceConfiguration(
    val model: String = "grok-voice-3.0",
    val voice: String = "eve",
    val reasoningEffort: String = "high",
) {
    init {
        require(model.isNotBlank() && model.length <= 100 && model.none(Char::isWhitespace)) { "Invalid Grok voice model." }
        require(voice.isNotBlank() && voice.length <= 100 && voice.none(Char::isWhitespace)) { "Invalid Grok voice ID." }
        require(reasoningEffort in listOf("high", "none")) { "Grok voice reasoning must be high or none." }
    }
}

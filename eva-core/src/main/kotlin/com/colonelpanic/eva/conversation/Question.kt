package com.colonelpanic.eva.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class QuestionSource { TEXT_AGENT, DEVICE_TASK }

@Serializable
enum class QuestionResolution { PENDING, SUBMITTING, ACCEPTED, FAILED, CANCELLED, INTERRUPTED }

@Serializable
enum class AnswerProvenance { TYPED, SPOKEN_FALLBACK, VOICE_MODEL, DEVICE_REVISION }

/** Each event is self-contained so a bounded history can retain the answer without its earlier question event. */
@Serializable
data class QuestionEvidence(
    val questionId: String,
    val taskId: String,
    val legId: String?,
    val source: QuestionSource,
    val question: String,
    val resolution: QuestionResolution = QuestionResolution.PENDING,
    val answer: String? = null,
    val provenance: AnswerProvenance? = null,
    val transcriptItemId: String? = null,
    val order: Long = 0,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    fun data(): JsonObject = Json.parseToJsonElement(encode()).jsonObject

    val waiting: Boolean get() = resolution == QuestionResolution.PENDING || resolution == QuestionResolution.SUBMITTING

    companion object {
        fun decode(text: String): QuestionEvidence = Json.decodeFromString(serializer(), text)
    }
}

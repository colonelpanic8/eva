package com.colonelpanic.eva.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Where a dictated request goes: a new conversation, or a follow-up or answer in [threadId]. */
data class DictationTarget(
    val threadId: String? = null,
    val questionId: String? = null,
    /** Show the conversation once the work is accepted, rather than staying where the user is. */
    val openConversation: Boolean = true,
)

sealed interface DictationResult {
    data class Heard(
        val text: String,
    ) : DictationResult

    data object Cancelled : DictationResult

    data class Failed(
        val reason: String,
    ) : DictationResult
}

/** System speech recognition. The recognizer app records, so EVA needs no microphone grant for it. */
class DictateSpeech : ActivityResultContract<String, DictationResult>() {
    override fun createIntent(
        context: Context,
        input: String,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, input)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): DictationResult = dictationResult(resultCode, intent?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
}

internal fun dictationResult(
    resultCode: Int,
    results: List<String>?,
): DictationResult =
    when (resultCode) {
        Activity.RESULT_OK -> {
            results
                ?.firstOrNull { it.isNotBlank() }
                ?.let { DictationResult.Heard(it.trim()) }
                ?: DictationResult.Failed("Speech recognition returned no words. Try again.")
        }

        Activity.RESULT_CANCELED -> {
            DictationResult.Cancelled
        }

        RecognizerIntent.RESULT_NO_MATCH -> {
            DictationResult.Failed("Speech recognition did not catch any words. Try again.")
        }

        RecognizerIntent.RESULT_NETWORK_ERROR -> {
            DictationResult.Failed("Speech recognition needs a network connection. Reconnect, or type the request.")
        }

        RecognizerIntent.RESULT_AUDIO_ERROR -> {
            DictationResult.Failed("Speech recognition could not record audio. Check that no other app holds the microphone.")
        }

        else -> {
            DictationResult.Failed("Speech recognition failed (result $resultCode). Try again, or type the request.")
        }
    }

/** The Material microphone; icons-core does not include it. */
internal val DictateIcon: ImageVector =
    ImageVector
        .Builder("Dictate", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                    "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
            ),
            fill = SolidColor(Color.Black),
        ).build()

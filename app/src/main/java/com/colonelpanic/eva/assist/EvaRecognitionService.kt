package com.colonelpanic.eva.assist

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognizer a voice interaction service is required to declare, and which becomes the
 * device default once EVA is chosen as the assistant. EVA does not transcribe speech itself,
 * so every request is forwarded to the recognizer the phone already had; see
 * [preferredRecognizer]. With nothing to forward to, the caller is told so immediately rather
 * than left waiting for a result that will not arrive.
 */
class EvaRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback,
    ) {
        release()
        val recognizer =
            createDelegate() ?: run {
                listener.report { this.error(SpeechRecognizer.ERROR_CLIENT) }
                return
            }
        delegate = recognizer
        recognizer.setRecognitionListener(Forwarder(listener))
        recognizer.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        delegate?.destroy()
        delegate = null
    }

    /** On-device recognition first: it answers without the request leaving the phone. */
    private fun createDelegate(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        val installed =
            packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { resolved ->
                    val service = resolved.serviceInfo ?: return@mapNotNull null
                    RecognizerCandidate(
                        packageName = service.packageName,
                        className = service.name,
                        preinstalled = service.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
        val chosen = preferredRecognizer(installed, packageName) ?: return null
        return SpeechRecognizer.createSpeechRecognizer(this, ComponentName(chosen.packageName, chosen.className))
    }

    /** A caller that has already gone away takes its binder with it; that is not an EVA failure. */
    private fun Callback.report(block: Callback.() -> Unit) {
        runCatching { block() }
    }

    private inner class Forwarder(
        private val callback: Callback,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callback.report { this.readyForSpeech(params) }

        override fun onBeginningOfSpeech() = callback.report { this.beginningOfSpeech() }

        override fun onRmsChanged(rmsdB: Float) = callback.report { this.rmsChanged(rmsdB) }

        override fun onBufferReceived(buffer: ByteArray?) = callback.report { this.bufferReceived(buffer) }

        override fun onEndOfSpeech() = callback.report { this.endOfSpeech() }

        override fun onError(error: Int) = callback.report { this.error(error) }

        override fun onResults(results: Bundle?) = callback.report { this.results(results) }

        override fun onPartialResults(partialResults: Bundle?) = callback.report { this.partialResults(partialResults) }

        override fun onSegmentResults(segmentResults: Bundle) =
            callback.report {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) this.segmentResults(segmentResults)
            }

        override fun onEndOfSegmentedSession() =
            callback.report {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) this.endOfSegmentedSession()
            }

        override fun onLanguageDetection(results: Bundle) =
            callback.report {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) this.languageDetection(results)
            }

        /** Vendor-specific and undefined outside the recognizer that emitted it. */
        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }
}

package com.voicedeviceagent.companion.keyword

import ai.onnxruntime.OrtEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Desktop smoke evaluation with generated silence and tones, not a speech-accuracy benchmark. */
object SyntheticEvaluation {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a directory containing the openWakeWord ONNX models." }
        val directory = File(args.single())
        val classifiers =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "onnx" && it.name !in setOf("melspectrogram.onnx", "embedding_model.onnx") }
                .associate { it.nameWithoutExtension to it.readBytes() }
        require(classifiers.isNotEmpty()) { "No classifiers found" }
        val models =
            OnnxWakeWordModels(
                File(directory, "melspectrogram.onnx").readBytes(),
                File(directory, "embedding_model.onnx").readBytes(),
                classifiers,
                OrtEnvironment.getEnvironment(),
            )
        var now = 0L
        val maxima = mutableMapOf<String, Float>()
        OpenWakeWordSpotter(
            models,
            classifiers.keys.map { PhraseSpec(it) },
            CoroutineScope(Dispatchers.Default),
            { now },
            onScores = { scores ->
                scores.forEach { (phrase, value) ->
                    check(value.isFinite()) { "Non-finite score for $phrase" }
                    maxima[phrase] = maxOf(maxima[phrase] ?: 0f, value)
                }
            },
        ).use { spotter ->
            var detections = 0
            repeat(500) { frame ->
                val samples =
                    ShortArray(480) { i ->
                        if (frame < 250) 0 else (4_000 * sin(2 * PI * 440 * (frame * 480 + i) / 24_000)).toInt().toShort()
                    }
                now += 20_000_000L
                detections += spotter.process(PcmFrame(samples, 24_000, now)).size
            }
            check(spotter.stats().chunks == 125L)
            println("10 s synthetic PCM at 24 kHz: ${spotter.stats().chunks} chunks, $detections detections; peak scores: $maxima")
        }
    }
}

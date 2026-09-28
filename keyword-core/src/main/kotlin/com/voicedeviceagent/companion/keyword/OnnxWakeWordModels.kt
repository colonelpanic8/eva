package com.voicedeviceagent.companion.keyword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * [WakeWordModels] on ONNX Runtime, from model bytes. The same class runs on Android
 * (`onnxruntime-android`) and in the desktop evaluation harness (`onnxruntime`).
 */
class OnnxWakeWordModels(
    melspectrogram: ByteArray,
    embedding: ByteArray,
    classifiers: Map<String, ByteArray>,
    private val env: OrtEnvironment,
    threads: Int = 1,
) : WakeWordModels {
    private val options =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
        }
    private val mel = env.createSession(melspectrogram, options)
    private val embed = env.createSession(embedding, options)
    private val classifierSessions = classifiers.mapValues { (_, bytes) -> env.createSession(bytes, options) }
    private val classifierFrames =
        classifierSessions.mapValues { (phrase, session) ->
            val shape =
                (
                    session.inputInfo.values
                        .single()
                        .info as TensorInfo
                ).shape
            require(shape.size == 3 && shape[2] == EMBEDDING_SIZE.toLong()) { "$phrase: unexpected input shape ${shape.toList()}" }
            shape[1].toInt()
        }

    override val phrases: Set<String> = classifiers.keys

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            mel.run(mapOf(mel.inputNames.single() to input)).use { result ->
                val output = result.get(0) as OnnxTensor
                val shape = output.info.shape
                val frames = shape[2].toInt()
                val bins = shape[3].toInt()
                val values = output.floatBuffer
                Array(frames) { f -> FloatArray(bins) { b -> values.get(f * bins + b) / 10f + 2f } }
            }
        }

    override fun embedding(mel: Array<FloatArray>): FloatArray {
        val flat = FloatBuffer.allocate(mel.size * MEL_BINS)
        mel.forEach { flat.put(it, 0, MEL_BINS) }
        flat.rewind()
        return OnnxTensor.createTensor(env, flat, longArrayOf(1, mel.size.toLong(), MEL_BINS.toLong(), 1)).use { input ->
            embed.run(mapOf(embed.inputNames.single() to input)).use { result ->
                val values = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(EMBEDDING_SIZE) { values.get(it) }
            }
        }
    }

    override fun inputFrames(phrase: String): Int = classifierFrames.getValue(phrase)

    override fun score(
        phrase: String,
        features: Array<FloatArray>,
    ): Float {
        val session = classifierSessions.getValue(phrase)
        val flat = FloatBuffer.allocate(features.size * EMBEDDING_SIZE)
        features.forEach { flat.put(it, 0, EMBEDDING_SIZE) }
        flat.rewind()
        return OnnxTensor.createTensor(env, flat, longArrayOf(1, features.size.toLong(), EMBEDDING_SIZE.toLong())).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { result ->
                (result.get(0) as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        classifierSessions.values.forEach { it.close() }
        embed.close()
        mel.close()
        options.close()
    }

    companion object {
        const val MEL_BINS = 32
        const val EMBEDDING_SIZE = 96
    }
}

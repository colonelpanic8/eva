package com.colonelpanic.eva.keyword

/** The three openWakeWord stages; see [OpenWakeWordPipeline] for how they are chained. */
interface WakeWordModels : AutoCloseable {
    val phrases: Set<String>

    /** Mel frames of 32 bins for PCM16 values given as floats, already scaled as `x / 10 + 2`. */
    fun melspectrogram(samples: FloatArray): Array<FloatArray>

    /** One 96-value embedding for [OpenWakeWordPipeline.EMBEDDING_WINDOW] mel frames. */
    fun embedding(mel: Array<FloatArray>): FloatArray

    /** How many consecutive embeddings the phrase classifier takes. */
    fun inputFrames(phrase: String): Int

    /** The phrase probability for the latest [inputFrames] embeddings, oldest first. */
    fun score(
        phrase: String,
        features: Array<FloatArray>,
    ): Float

    companion object {
        const val MEL_BINS = 32
        const val EMBEDDING_SIZE = 96
    }
}

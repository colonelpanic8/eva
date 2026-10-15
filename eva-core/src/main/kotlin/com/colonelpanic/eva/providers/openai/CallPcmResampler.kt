package com.colonelpanic.eva.providers.openai

/** Stateful linear interpolation; rational sample positions preserve continuity across arbitrary chunks. */
internal class CallPcmResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private var inputIndex = 0L
    private var nextOutput = 0L
    private var previous: Short = 0

    init {
        require(inputRate in 8_000..48_000 && outputRate in 8_000..48_000)
    }

    fun convert(samples: ShortArray): ShortArray {
        if (inputRate == outputRate) return samples.copyOf()
        val output = ArrayList<Short>()
        for (sample in samples) {
            val right = inputIndex * outputRate
            while (nextOutput <= right) {
                val value =
                    if (inputIndex == 0L) {
                        sample.toDouble()
                    } else {
                        val fraction = (nextOutput - (inputIndex - 1) * outputRate).toDouble() / outputRate
                        previous + (sample.toInt() - previous) * fraction
                    }
                output += value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                nextOutput += inputRate
            }
            previous = sample
            inputIndex++
        }
        return output.toShortArray()
    }
}

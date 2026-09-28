package com.voicedeviceagent.companion.keyword

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming rational resampler for mono PCM16: upsample by L, low-pass below the lower Nyquist
 * frequency with a Blackman-windowed sinc, decimate by M. Unlike linear interpolation it keeps
 * the 4–8 kHz band that the keyword models see free of aliased energy when going 24 → 16 kHz.
 */
class PolyphaseResampler(
    val inRate: Int,
    val outRate: Int,
    tapsPerRate: Int = 24,
) {
    private val up: Int
    private val down: Int
    private val taps: Int
    private val phases: Array<FloatArray>
    private var history: FloatArray
    private var position = 0L

    init {
        require(inRate > 0 && outRate > 0)
        val divisor = gcd(inRate, outRate)
        up = outRate / divisor
        down = inRate / divisor
        taps = tapsPerRate * maxOf(up, down) / up
        val length = taps * up
        val cutoff = CUTOFF / maxOf(up, down)
        val center = (length - 1) / 2.0
        val prototype =
            DoubleArray(length) { j ->
                val t = j - center
                val sinc = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                val window = 0.42 - 0.5 * cos(2 * PI * j / (length - 1)) + 0.08 * cos(4 * PI * j / (length - 1))
                up * sinc * window
            }
        phases = Array(up) { phase -> FloatArray(taps) { k -> prototype[phase + k * up].toFloat() } }
        history = FloatArray(taps - 1)
    }

    fun process(input: ShortArray): ShortArray {
        if (up == 1 && down == 1) return input.copyOf()
        val buffer = FloatArray(history.size + input.size)
        history.copyInto(buffer)
        for (i in input.indices) buffer[history.size + i] = input[i].toFloat()
        val out = ShortArray(((input.size.toLong() * up - position) / down + 1).toInt().coerceAtLeast(0))
        var count = 0
        var p = position
        while (true) {
            val base = Math.floorDiv(p, up.toLong()).toInt()
            if (base >= input.size) break
            val coefficients = phases[(p - base.toLong() * up).toInt()]
            val newest = history.size + base
            var acc = 0f
            for (k in 0 until taps) acc += coefficients[k] * buffer[newest - k]
            out[count++] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            p += down
        }
        position = p - input.size.toLong() * up
        history = buffer.copyOfRange(buffer.size - history.size, buffer.size)
        return out.copyOf(count)
    }

    private companion object {
        const val CUTOFF = 0.45

        tailrec fun gcd(
            a: Int,
            b: Int,
        ): Int = if (b == 0) a else gcd(b, a % b)
    }
}

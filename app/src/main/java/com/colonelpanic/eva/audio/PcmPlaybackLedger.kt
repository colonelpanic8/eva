package com.colonelpanic.eva.audio

/** Frame positions survive queueing and completion notifications racing a speech interruption. */
internal class PcmPlaybackLedger(
    private val sampleRate: Int,
) {
    private data class Output(
        val start: Long,
        var end: Long,
        var audible: Boolean,
    )

    private val outputs = linkedMapOf<String, Output>()
    private val pending = linkedSetOf<String>()
    private val completed = linkedMapOf<String, Long>()
    private var written = 0L
    val pendingIds: List<String> get() = pending.toList()

    fun enqueue(id: String) {
        pending += id
    }

    fun write(
        id: String,
        frames: Int,
        audible: Boolean,
    ): Boolean {
        if (frames == 0) return false
        val first = id !in outputs
        val output = outputs.getOrPut(id) { Output(written, written, audible) }
        written += frames
        output.end = written
        output.audible = output.audible && audible
        return first
    }

    /** Null while still playing; otherwise whether the complete response was rendered unmuted. */
    fun finish(
        id: String,
        head: Long,
    ): Boolean? {
        val output = outputs[id]
        if (output != null && head < output.end) return null
        pending.remove(id)
        outputs.remove(id)
        completed[id] = output?.let { millis(it.end - it.start) } ?: 0
        return output?.audible == true
    }

    fun mute() {
        outputs.values.forEach { it.audible = false }
    }

    fun interrupt(head: Long): Map<String, Long> {
        val positions =
            completed +
                pending.associateWith { id ->
                    outputs[id]?.let { millis((head.coerceAtMost(it.end) - it.start).coerceAtLeast(0)) } ?: 0
                }
        outputs.clear()
        pending.clear()
        completed.clear()
        written = 0
        return positions
    }

    private fun millis(frames: Long): Long = frames * 1000 / sampleRate
}

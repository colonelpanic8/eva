package com.colonelpanic.eva.conversation

/**
 * One delegating speech session's captions, placed on its timeline. Fragments are kept exactly as
 * received and ordered by their timestamps, so speech transcribed late still lands where it was
 * said. A delegation names only a timeline offset; [speech] reads what was said before it.
 */
internal class LiveTranscript {
    data class Fragment(
        val role: String,
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    /** A run of one speaker's fragments, journaled as one message. */
    data class Segment(
        val role: String,
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    private val fragments = mutableListOf<Fragment>()
    private val open = linkedMapOf<String, MutableList<Fragment>>()

    /** Adds a fragment and returns the segments it closed: its speaker paused, or the other speaker took over. */
    fun add(fragment: Fragment): List<Segment> {
        fragments.add(fragments.indexOfFirst { it.startMs > fragment.startMs }.takeIf { it >= 0 } ?: fragments.size, fragment)
        val closed = mutableListOf<Segment>()
        open[fragment.role]?.let { run ->
            if (fragment.startMs - run.maxOf { it.endMs } > SEGMENT_GAP_MS) closed += listOfNotNull(close(fragment.role))
        }
        // Overlapping speech leaves both runs open; a speaker only yields once the other starts after them.
        open.keys.filter { it != fragment.role && fragment.startMs >= open.getValue(it).maxOf { run -> run.endMs } }.forEach {
            closed += listOfNotNull(close(it))
        }
        open.getOrPut(fragment.role) { mutableListOf() } += fragment
        return closed
    }

    /** Closes every open run, for a delegation about to read the journal or an ending session. */
    fun drain(): List<Segment> = open.keys.toList().mapNotNull(::close)

    /** [role]'s fragments that started after [afterMs] and no later than [throughMs], joined exactly. */
    fun speech(
        role: String,
        afterMs: Long,
        throughMs: Long,
    ): String = fragments.filter { it.role == role && it.startMs > afterMs && it.startMs <= throughMs }.joinToString("") { it.text }

    /** Whether any caption has been placed at or after [offsetMs], so earlier speech has likely been transcribed. */
    fun reached(offsetMs: Long): Boolean = fragments.any { it.startMs >= offsetMs }

    /**
     * Both speakers' segments overlapping [fromMs]..[throughMs], newest kept when [limitChars] runs
     * out. The flag says whether older speech was left out.
     */
    fun excerpt(
        fromMs: Long,
        throughMs: Long,
        limitChars: Int,
    ): Pair<List<Segment>, Boolean> {
        val segments = mutableListOf<Segment>()
        fragments.filter { it.endMs >= fromMs && it.startMs <= throughMs }.forEach { fragment ->
            val last = segments.lastOrNull()
            if (last?.role == fragment.role) {
                segments[segments.lastIndex] = last.copy(text = last.text + fragment.text, endMs = maxOf(last.endMs, fragment.endMs))
            } else {
                segments += Segment(fragment.role, fragment.text, fragment.startMs, fragment.endMs)
            }
        }
        val kept = mutableListOf<Segment>()
        var used = 0
        for (segment in segments.asReversed()) {
            if (used + segment.text.length > limitChars) break
            kept.add(0, segment)
            used += segment.text.length
        }
        return kept to (kept.size < segments.size)
    }

    private fun close(role: String): Segment? {
        val run = open.remove(role)?.sortedBy { it.startMs } ?: return null
        val text = run.joinToString("") { it.text }.trim()
        return if (text.isEmpty()) null else Segment(role, text, run.first().startMs, run.maxOf { it.endMs })
    }

    companion object {
        /** A pause this long in one speaker's captions starts a new message. */
        const val SEGMENT_GAP_MS = 2_000L
    }
}

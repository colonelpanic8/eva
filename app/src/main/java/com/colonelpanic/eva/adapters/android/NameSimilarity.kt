package com.colonelpanic.eva.adapters.android

import java.text.Normalizer

/**
 * How closely a heard name fits a stored one, from 0 (unrelated) to 1 (the same words). Speech
 * recognition routinely doubles a letter or picks another spelling of the same sound, so a word may
 * match by sounding alike, by prefix, or by a small edit. A near miss on one word of a full name
 * still ranks far above a contact who shares only the other word, while a single short word never
 * matches a different name by a typo alone.
 */
object NameSimilarity {
    /** Every heard word was found, spelled the same or plausibly misheard. */
    const val STRONG = 0.75

    fun score(
        query: String,
        name: String,
    ): Double {
        val heard = words(query)
        val stored = words(name)
        if (heard.isEmpty() || stored.isEmpty()) return 0.0
        // "Maryann" and "Mary Ann" are one name split differently.
        if (heard.joinToString("") == stored.joinToString("")) return 1.0
        val unused = stored.toMutableList()
        var total = 0.0
        for (word in heard.sortedByDescending(String::length)) {
            val best = unused.maxByOrNull { word(word, it) } ?: break
            val similarity = word(word, best)
            if (similarity > 0) unused.remove(best)
            total += similarity
        }
        return total / heard.size
    }

    /** Whether the query accounts for every word of the stored name, so "Sarah" fits a contact named just Sarah best. */
    fun complete(
        query: String,
        name: String,
    ): Boolean {
        val heard = words(query)
        return words(name).all { stored -> heard.any { word(it, stored) >= STRONG } }
    }

    fun words(value: String): List<String> =
        Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(apostrophes, "")
            .lowercase()
            .split(separators)
            .filter(String::isNotEmpty)

    internal fun word(
        heard: String,
        stored: String,
    ): Double {
        if (heard == stored) return 1.0
        var best = 0.0
        val key = sound(heard)
        if (key.isNotEmpty() && key == sound(stored)) best = maxOf(best, SOUNDS_ALIKE)
        if (heard.length >= 3 && stored.startsWith(heard)) best = maxOf(best, PREFIX)
        if (stored.length >= 3 && heard.startsWith(stored)) best = maxOf(best, EXTENSION)
        // Misheard names keep their first sound; Jason and Mason are different people.
        if (heard.first() == stored.first()) {
            val edits = distance(heard, stored)
            if (edits in 1..allowedEdits(minOf(heard.length, stored.length))) best = maxOf(best, EDITED - (edits - 1) * EDIT_PENALTY)
        }
        return best
    }

    /** Short words have too many one-letter neighbours (Mark, Mary) to forgive a typo in them. */
    private fun allowedEdits(length: Int) =
        when {
            length <= 4 -> 0
            length <= 7 -> 1
            else -> 2
        }

    /** Optimal string alignment distance: an adjacent swap counts as one edit. */
    internal fun distance(
        a: String,
        b: String,
    ): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    /**
     * Folds spellings of one sound together and collapses doubled letters, so Mallison and Malison,
     * Stephen and Steven, or Jon and John share a key. Vowels stay: Mary and Maria are different names.
     */
    internal fun sound(word: String): String {
        var folded = word.filter(Char::isLetter)
        for ((spelling, sound) in soundAlikes) folded = folded.replace(spelling, sound)
        folded = folded.map { letterSounds[it] ?: it }.filter { it != 'h' }.joinToString("")
        val collapsed = StringBuilder()
        for (letter in folded) if (collapsed.lastOrNull() != letter) collapsed.append(letter)
        return collapsed.toString()
    }

    private const val SOUNDS_ALIKE = 0.9
    private const val PREFIX = 0.85
    private const val EXTENSION = 0.7
    private const val EDITED = 0.7
    private const val EDIT_PENALTY = 0.1

    private val marks = Regex("\\p{M}+")
    private val apostrophes = Regex("['’]")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val soundAlikes = listOf("ph" to "f", "ck" to "k", "sch" to "sk", "wr" to "r", "kn" to "n")
    private val letterSounds = mapOf('c' to 'k', 'q' to 'k', 'z' to 's', 'y' to 'i', 'v' to 'f')
}

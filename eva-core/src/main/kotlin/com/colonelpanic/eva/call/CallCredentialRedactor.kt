package com.colonelpanic.eva.call

internal class CallCredentialRedactor {
    private val patterns = mutableMapOf<String, Regex>()

    fun remember(value: String) {
        patterns.getOrPut(value) {
            Regex(
                value
                    .map { key ->
                        val literal = Regex.escape(key.toString())
                        val word = DIGIT_WORDS[key]
                        if (word == null) literal else "(?:$literal|(?<![a-z])(?:$word)(?![a-z]))"
                    }.joinToString("[\\s\\p{P}]*"),
                RegexOption.IGNORE_CASE,
            )
        }
    }

    fun clean(text: String): String = patterns.values.fold(text) { result, pattern -> pattern.replace(result, "[credential]") }

    private companion object {
        val DIGIT_WORDS =
            mapOf(
                '0' to "zero|oh",
                '1' to "one",
                '2' to "two",
                '3' to "three",
                '4' to "four",
                '5' to "five",
                '6' to "six",
                '7' to "seven",
                '8' to "eight",
                '9' to "nine",
            )
    }
}

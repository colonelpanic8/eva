package com.colonelpanic.eva.adapters.declarative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A conservative source audit, not an Android ICU execution test. */
class ExtensionRegexPortabilityTest {
    private val main =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/java/com/colonelpanic/eva") }
            .first { it.isDirectory }

    @Test
    fun `all extension and shared validation regexes use the audited portable subset`() {
        val files =
            listOf("adapters/declarative", "capability/extensions").flatMap { dir ->
                File(main, dir).walkTopDown().filter { it.extension == "kt" }.toList()
            } + listOf("capability/BoundedJson.kt", "adapters/android/MessageTargets.kt").map { File(main, it) }
        var count = 0
        for (file in files) {
            val source = file.readText()
            val constructors = Regex("\\bRegex\\s*\\(").findAll(source).count()
            val literals = Regex("\\bRegex\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)").findAll(source).toList()
            assertEquals("${file.name}: audit requires literal regexes without options", constructors, literals.size)
            assertFalse("${file.name}: unreviewed regex entry point", source.contains(".toRegex(") || source.contains("Pattern.compile("))
            for (literal in literals) {
                val pattern = literal.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
                assertTrue("${file.name}: non-portable regex $pattern", portable(pattern))
                Regex(pattern)
                count++
            }
        }
        assertTrue("Audit must exercise the extension patterns", count >= 10)
    }

    @Test
    fun `audit rejects the device regression and other unreviewed constructs`() {
        for (pattern in listOf(
            "\\{([A-Za-z_][A-Za-z0-9_]{0,63})}",
            "a{literal}",
            "\\p{Letter}",
            "\\h+",
            "\\R",
            "(?i)a",
            "a++",
            "[a-z&&[^b]]",
        )) {
            assertFalse(pattern, portable(pattern))
        }
        assertTrue(portable("\\{([A-Za-z_][A-Za-z0-9_]{0,63})\\}"))
    }

    private fun portable(pattern: String): Boolean {
        var inClass = false
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when (char) {
                '\\' -> {
                    if (++index >= pattern.length) return false
                    val escaped = pattern[index]
                    if (escaped.isLetterOrDigit() && escaped !in "dswDSW") return false
                }

                '[' -> {
                    if (inClass) return false
                    inClass = true
                }

                ']' -> {
                    if (!inClass) return false
                    inClass = false
                }

                '&' -> {
                    if (inClass && pattern.getOrNull(index + 1) == '&') return false
                }

                '{' -> {
                    if (!inClass) {
                        val end = pattern.indexOf('}', index + 1)
                        if (end < 0 || !Regex("[0-9]+(,[0-9]*)?").matches(pattern.substring(index + 1, end))) return false
                        index = end
                        if (pattern.getOrNull(index + 1) in listOf('+', '?')) return false
                    }
                }

                '}' -> {
                    if (!inClass) return false
                }

                '(' -> {
                    if (!inClass && pattern.getOrNull(index + 1) == '?' &&
                        pattern.getOrNull(index + 2) !in listOf(':', '=', '!')
                    ) {
                        return false
                    }
                }

                '*', '+', '?' -> {
                    if (!inClass && pattern.getOrNull(index + 1) in listOf('+', '?')) return false
                }
            }
            index++
        }
        return !inClass
    }
}

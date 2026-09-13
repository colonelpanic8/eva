package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchesTest {
    @Test
    fun `the closest name leads and its most callable number comes first`() {
        val matches =
            listOf(
                ContactMatch("Sarah Chenoweth", listOf(ContactPhone("650-555-0123", "home"))),
                ContactMatch("Sarah", listOf(ContactPhone("+1 415-555-0101", "work"), ContactPhone("+1 415-555-0100", "mobile"))),
            )
        assertEquals(
            "Best match for \"sarah\": Sarah: mobile +1 415-555-0100, work +1 415-555-0101. " +
                "Other matches: Sarah Chenoweth: home 650-555-0123. " + ContactMatches.PICK_BEST,
            ContactMatches.describe("sarah", matches),
        )
    }

    @Test
    fun `a surname match outranks a mere substring`() {
        val matches =
            listOf(
                ContactMatch("Mark Jensen", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Ellen Marks", listOf(ContactPhone("2025550102", "mobile"))),
                ContactMatch("Dana Mark", listOf(ContactPhone("2025550103", "mobile"))),
            )
        assertEquals(listOf("Dana Mark", "Mark Jensen", "Ellen Marks"), ContactMatches.rank("Mark", matches).map { it.name })
    }

    @Test
    fun `equally good matches are flagged as needing a question`() {
        val matches =
            listOf(
                ContactMatch("Sarah Chen", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Sarah Kim", listOf(ContactPhone("2025550102", "mobile"))),
            )
        val described = ContactMatches.describe("Sarah", matches)
        assertTrue(described.endsWith(ContactMatches.AMBIGUOUS))
    }

    @Test
    fun `no match and overflow are reported so the model does not invent a number`() {
        assertEquals("No contact with a phone number matches \"Zed\".", ContactMatches.describe("Zed", emptyList()))
        val many = (1..7).map { ContactMatch("Sam $it", listOf(ContactPhone("202555010$it", "mobile"))) }
        val described = ContactMatches.describe("Sam", many)
        assertTrue(described.startsWith("Best match for \"Sam\": Sam 1: mobile 2025550101."))
        assertTrue(described.contains("2 further matches were not listed."))
        assertEquals(false, described.contains("Sam 6"))
    }

    @Test
    fun `one person duplicated across accounts is listed once with both numbers`() {
        val matches =
            listOf(
                ContactMatch("Ivan Malison", listOf(ContactPhone("301-244-8534", "mobile"))),
                ContactMatch("Ivan Malison", listOf(ContactPhone("(301) 244-8534", "home"), ContactPhone("4155550100", "work"))),
            )
        assertEquals(
            "Best match for \"Ivan\": Ivan Malison: mobile 301-244-8534, work 4155550100. " + ContactMatches.PICK_BEST,
            ContactMatches.describe("Ivan", matches),
        )
    }
}

package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMatchesTest {
    @Test
    fun `matches list every contact with its numbers and kinds`() {
        val matches =
            listOf(
                ContactMatch("Sarah Chen", listOf(ContactPhone("+1 415-555-0100", "mobile"), ContactPhone("+1 415-555-0101", "work"))),
                ContactMatch("Sarah Kim", listOf(ContactPhone("650-555-0123", "home"))),
            )
        assertEquals(
            "Found 2 contacts matching \"Sarah\": Sarah Chen: mobile +1 415-555-0100, work +1 415-555-0101; Sarah Kim: home 650-555-0123.",
            ContactMatches.describe("Sarah", matches),
        )
    }

    @Test
    fun `no match and overflow are reported so the model can ask instead of guessing`() {
        assertEquals("No contact with a phone number matches \"Zed\".", ContactMatches.describe("Zed", emptyList()))
        val many = (1..7).map { ContactMatch("Sam $it", listOf(ContactPhone("202555010$it", "mobile"))) }
        val described = ContactMatches.describe("Sam", many)
        assertEquals(true, described.startsWith("Found first 5 of 7 contacts matching \"Sam\": Sam 1: mobile 2025550101;"))
        assertEquals(false, described.contains("Sam 6"))
    }
}

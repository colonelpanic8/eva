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
    fun `a misheard surname picks the person rather than every contact sharing the first name`() {
        val alexes =
            listOf("Alex Adams", "Alex Baker", "Alex Chen", "Alex Diaz", "Alex Evans", "Alex Malison", "Alex Smith")
                .mapIndexed { index, name -> ContactMatch(name, listOf(ContactPhone("202555010$index", "mobile"))) }
        assertEquals(
            "Best match for \"Alex Mallison\": Alex Malison (approximate match): mobile 2025550105. " + ContactMatches.PICK_BEST,
            ContactMatches.describe("Alex Mallison", alexes),
        )
    }

    @Test
    fun `a name matching only in part is not acted on`() {
        val matches = listOf(ContactMatch("Alex Smith", listOf(ContactPhone("2025550101", "mobile"))))
        assertTrue(ContactMatches.describe("Alex Mallison", matches).endsWith(ContactMatches.PARTIAL))
    }

    @Test
    fun `the exact name beats a near spelling of it`() {
        val matches =
            listOf(
                ContactMatch("Alex Malison", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Alex Madison", listOf(ContactPhone("2025550102", "mobile"))),
            )
        val described = ContactMatches.describe("Alex Madison", matches)
        assertTrue(described.startsWith("Best match for \"Alex Madison\": Alex Madison: mobile 2025550102."))
        assertTrue(described.endsWith(ContactMatches.PICK_BEST))
    }

    @Test
    fun `of two people with the same name the one the user texts is chosen`() {
        val matches =
            listOf(
                ContactMatch("Sarah Chen", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Sarah Kim", listOf(ContactPhone("2025550102", "mobile"))),
            )
        val history = ContactHistory(messaged = mapOf("+12025550102" to NOW - 3 * DAY), key = usPhoneNumbers)
        assertEquals(
            "Best match for \"Sarah\": Sarah Kim (last in touch 3 days ago): mobile 2025550102. " +
                "Other matches: Sarah Chen: mobile 2025550101. " + ContactMatches.PICK_RECENT,
            ContactMatches.describe("Sarah", matches, history = history, nowMillis = NOW),
        )
    }

    @Test
    fun `when both namesakes are in touch the most recent leads but the user is still asked`() {
        val matches =
            listOf(
                ContactMatch("Sarah Chen", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Sarah Kim", listOf(ContactPhone("2025550102", "mobile"))),
            )
        val history = ContactHistory(messaged = mapOf("+12025550101" to NOW - 40 * DAY, "+12025550102" to NOW - DAY), key = usPhoneNumbers)
        assertEquals(listOf("Sarah Kim", "Sarah Chen"), ContactMatches.rank("Sarah", matches, history = history).map { it.name })
        assertTrue(ContactMatches.describe("Sarah", matches, history = history, nowMillis = NOW).endsWith(ContactMatches.AMBIGUOUS))
    }

    @Test
    fun `the number last used for a person leads over their mobile`() {
        val matches =
            listOf(ContactMatch("Dana Mark", listOf(ContactPhone("2025550101", "mobile"), ContactPhone("2025550199", "work"))))
        val history = ContactHistory(chosen = mapOf("+12025550199" to NOW), key = usPhoneNumbers)
        assertTrue(
            ContactMatches
                .describe("Dana Mark", matches, history = history, nowMillis = NOW)
                .startsWith(
                    "Best match for \"Dana Mark\": Dana Mark (last in touch today): work 2025550199 (last used), mobile 2025550101.",
                ),
        )
    }

    @Test
    fun `a nickname finds the person and first names can be searched alone`() {
        val robert =
            ContactMatch("Robert Hale", listOf(ContactPhone("2025550101", "mobile")), "Robert", "Hale", nicknames = listOf("Bob"))
        val bobby = ContactMatch("Bobby Hale", listOf(ContactPhone("2025550102", "mobile")), "Bobby", "Hale")
        assertEquals(
            "Best match for \"Bob\": Robert Hale (nickname Bob): mobile 2025550101. " +
                "Other matches: Bobby Hale (approximate match): mobile 2025550102. " + ContactMatches.PICK_BEST,
            ContactMatches.describe("Bob", listOf(robert, bobby)),
        )
        assertEquals(emptyList<ContactMatch>(), ContactMatches.rank("Hale", listOf(robert, bobby), ContactField.GIVEN))
        assertEquals(2, ContactMatches.rank("Hale", listOf(robert, bobby), ContactField.FAMILY).size)
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

    @Test
    fun `different people with identical names remain ambiguous`() {
        val matches =
            listOf(
                ContactMatch("Alex Malison", listOf(ContactPhone("2025550101", "mobile"))),
                ContactMatch("Alex Malison", listOf(ContactPhone("2025550102", "mobile"))),
            )
        assertEquals(2, ContactMatches.rank("Alex Mallison", matches).size)
        assertTrue(ContactMatches.describe("Alex Mallison", matches).endsWith(ContactMatches.AMBIGUOUS))
        val history = ContactHistory(messaged = mapOf("+12025550102" to NOW), key = usPhoneNumbers)
        assertEquals(
            "2025550102",
            ContactMatches
                .rank("Alex Mallison", matches, history = history)
                .first()
                .phones
                .first()
                .number,
        )
        assertTrue(ContactMatches.describe("Alex Mallison", matches, history = history).endsWith(ContactMatches.PICK_RECENT))
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_800_000_000_000L
    }
}

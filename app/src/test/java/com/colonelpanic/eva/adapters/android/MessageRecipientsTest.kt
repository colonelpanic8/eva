package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRecipientsTest {
    @Test
    fun `a comma separated list addresses a group and drops a repeated number`() {
        assertEquals(
            listOf("+1 (202) 555-0100", "2025550101"),
            MessageRecipients.parse("+1 (202) 555-0100, 2025550101, 202 555 0101"),
        )
    }

    @Test
    fun `anything that is not a plain number is refused before it reaches an app`() {
        listOf(
            "2025550100;2025550101",
            "2025550100?body=Injected",
            "Kat",
            "",
            ",",
            (1..11).joinToString(",") { "202555010$it" },
        ).forEach { assertNull(it, MessageRecipients.parse(it)) }
    }

    @Test
    fun `normalizing keeps the country prefix and discards dictation punctuation`() {
        assertEquals("+12025550100", MessageRecipients.normalize("+1 (202) 555-0100"))
        assertEquals("2025550100", MessageRecipients.normalize("202-555-0100"))
    }
}

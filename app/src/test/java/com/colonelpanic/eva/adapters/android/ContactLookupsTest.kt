package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactLookupsTest {
    @Test
    fun `wildcards in a name are matched literally`() {
        assertEquals("%50\\% Off\\_Deals%", ContactLookups.contains("50% Off_Deals"))
    }

    @Test
    fun `an id selection binds every id it keeps and keeps no more than the cap`() {
        val (selection, arguments) = ContactLookups.idSelection("contact_id", listOf(4L, 7L))
        assertEquals("contact_id IN (?,?)" to listOf("4", "7"), selection to arguments.toList())
        val (bounded, many) = ContactLookups.idSelection("contact_id", (1L..80L).toList())
        assertEquals(ContactLookups.MAX_CONTACT_IDS, many.size)
        assertEquals(ContactLookups.MAX_CONTACT_IDS, bounded.count { it == '?' })
    }

    @Test
    fun `an unknown or absent field falls back to the whole displayed name`() {
        assertEquals(ContactField.DISPLAY, ContactField.of(null))
        assertEquals(ContactField.DISPLAY, ContactField.of("nickname"))
        assertEquals(ContactField.FAMILY, ContactField.of("family"))
    }
}

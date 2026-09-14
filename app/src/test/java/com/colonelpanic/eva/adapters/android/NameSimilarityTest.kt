package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSimilarityTest {
    @Test
    fun `a doubled letter in a heard surname still finds the person`() {
        val misheard = NameSimilarity.score("Alex Mallison", "Alex Malison")
        assertTrue(misheard >= NameSimilarity.STRONG)
        assertTrue(misheard > NameSimilarity.score("Alex Mallison", "Alex Smith") + 0.3)
    }

    @Test
    fun `spellings of the same sound match`() {
        listOf("jon" to "john", "steven" to "stephen", "katherine" to "catherine", "bryan" to "brian", "sara" to "sarah")
            .forEach { (heard, stored) -> assertTrue("$heard ~ $stored", NameSimilarity.word(heard, stored) >= NameSimilarity.STRONG) }
    }

    @Test
    fun `a different name one letter away is never a strong match on its own`() {
        assertEquals(0.0, NameSimilarity.word("mark", "mary"), 0.0)
        assertEquals(0.0, NameSimilarity.word("jason", "mason"), 0.0)
        assertEquals(0.0, NameSimilarity.word("mary", "maria"), 0.0)
        assertTrue(NameSimilarity.score("Carla", "Carly Jones") < NameSimilarity.STRONG)
    }

    @Test
    fun `a name split or accented differently is the same name`() {
        assertEquals(1.0, NameSimilarity.score("Maryann Lee", "Mary Ann Lee"), 0.0)
        assertEquals(1.0, NameSimilarity.score("Jose Alvarez", "José Álvarez"), 0.0)
        assertEquals(1.0, NameSimilarity.score("Conor OBrien", "Conor O'Brien"), 0.0)
    }

    @Test
    fun `numbers in names are not matched by sound`() {
        assertEquals(0.0, NameSimilarity.score("Sam 1", "Office 2"), 0.0)
    }
}

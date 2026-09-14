package com.colonelpanic.eva.ui

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationItemsTest {
    @Test
    fun `fractional width preserves unbounded intrinsic constraints`() {
        val constraints = Constraints(maxWidth = Constraints.Infinity)

        assertEquals(constraints, constraints.withMaxWidthFraction(0.92f))
    }

    @Test
    fun `fractional width caps bounded layout constraints`() {
        val constraints = Constraints(minWidth = 900, maxWidth = 1_000)

        assertEquals(
            Constraints(minWidth = 850, maxWidth = 850),
            constraints.withMaxWidthFraction(0.85f),
        )
    }
}

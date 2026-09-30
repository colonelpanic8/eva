package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalStateTest {
    @Test
    fun `editable fields report long text in full until the shared budget runs out`() {
        val budget = intArrayOf(PortalState.FIELD_BUDGET_CHARS)
        val long = "x".repeat(9_000)

        val reported = (1..7).map { PortalState.reportedText(long, editable = true, fieldBudget = budget).length }

        assertEquals(listOf(9_000, 9_000, 9_000, 9_000, 9_000, 9_000, 500), reported)
        assertEquals(500, PortalState.reportedText("y".repeat(600), editable = false, fieldBudget = intArrayOf(60_000)).length)
    }
}

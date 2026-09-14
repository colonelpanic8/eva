package com.colonelpanic.eva.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPickerTest {
    private val account = listOf("gpt-6-astra", "gpt-5.6-luna", "gpt-5.6-sol")

    @Test
    fun `the account's models stay listed while the field holds the current choice`() {
        val candidates = modelCandidates(account, selected = "gpt-5.6-sol", defaultModel = "gpt-5.6-sol")
        assertEquals(
            listOf("gpt-5.6-luna", "gpt-5.6-sol", "gpt-6-astra"),
            listedModels(candidates, draft = "gpt-5.6-sol", editing = false),
        )
    }

    @Test
    fun `typing narrows the list`() {
        val candidates = modelCandidates(account, selected = "gpt-5.6-sol", defaultModel = "gpt-5.6-sol")
        assertEquals(listOf("gpt-5.6-luna", "gpt-5.6-sol"), listedModels(candidates, draft = "5.6", editing = true))
        assertEquals(candidates, listedModels(candidates, draft = "", editing = true))
    }

    @Test
    fun `the default and the current choice are reachable without the account list`() {
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-6-astra"),
            modelCandidates(emptyList(), selected = "gpt-6-astra", defaultModel = "gpt-5.6-sol"),
        )
    }
}

package com.colonelpanic.eva.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognizerCandidateTest {
    private val self = "com.colonelpanic.eva"
    private val eva = RecognizerCandidate(self, "$self.assist.EvaRecognitionService", preinstalled = false)
    private val preinstalled = RecognizerCandidate("com.android.speech", "Recognition", preinstalled = true)
    private val installed = RecognizerCandidate("com.example.dictation", "Recognition", preinstalled = false)

    @Test
    fun `a preinstalled recognizer is preferred over a sideloaded one`() {
        assertEquals(preinstalled, preferredRecognizer(listOf(eva, installed, preinstalled), self))
    }

    @Test
    fun `an installed recognizer is used when nothing is preinstalled`() {
        assertEquals(installed, preferredRecognizer(listOf(eva, installed), self))
    }

    @Test
    fun `EVA never forwards to itself`() {
        assertNull(preferredRecognizer(listOf(eva), self))
        assertNull(preferredRecognizer(emptyList(), self))
    }
}

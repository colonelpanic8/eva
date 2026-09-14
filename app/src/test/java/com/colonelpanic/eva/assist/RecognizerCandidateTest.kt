package com.colonelpanic.eva.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognizerCandidateTest {
    private val self = "com.colonelpanic.eva"
    private val selfClass = "$self.assist.EvaRecognitionService"
    private val eva = RecognizerCandidate(self, selfClass, preinstalled = false)
    private val preinstalled = RecognizerCandidate("com.android.speech", "Recognition", preinstalled = true)
    private val installed = RecognizerCandidate("com.example.dictation", "Recognition", preinstalled = false)

    private fun choose(
        candidates: List<RecognizerCandidate>,
        selfPackage: String = self,
    ) = preferredRecognizer(candidates, selfPackage, selfClass)

    @Test
    fun `a preinstalled recognizer is preferred over a sideloaded one`() {
        assertEquals(preinstalled, choose(listOf(eva, installed, preinstalled)))
    }

    @Test
    fun `an installed recognizer is used when nothing is preinstalled`() {
        assertEquals(installed, choose(listOf(eva, installed)))
    }

    @Test
    fun `release and debug installations cannot delegate to each other`() {
        val debug = eva.copy(packageName = "$self.debug")
        assertNull(choose(listOf(debug)))
        assertNull(choose(listOf(eva), selfPackage = debug.packageName))
        assertEquals(installed, choose(listOf(eva, debug, installed)))
    }

    @Test
    fun `EVA never forwards to itself`() {
        assertNull(choose(listOf(eva)))
        assertNull(choose(emptyList()))
    }
}

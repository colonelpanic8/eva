package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.InvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSendResultsTest {
    @Test
    fun `a confirmed send reports the text that went out`() {
        val outcome = SmsSendResults.describe("+14155550100", "On my way\nsee you soon", SmsSendReport(parts = 1))
        assertEquals(InvocationStatus.COMPLETED, outcome.status)
        assertEquals("Text sent to +14155550100: \"On my way see you soon\".", outcome.message)
    }

    @Test
    fun `a long message is previewed within the cap and notes its parts`() {
        val outcome = SmsSendResults.describe("+14155550100", "a".repeat(400), SmsSendReport(parts = 3))
        assertEquals(SmsSendResults.MAX_PREVIEW, SmsSendResults.preview("a".repeat(400)).length)
        assertTrue(outcome.message.endsWith("…\" (3 parts)."))
    }

    @Test
    fun `an unconfirmed send is not claimed as sent`() {
        val outcome = SmsSendResults.describe("+14155550100", "hi", SmsSendReport(parts = 1, timedOut = true))
        assertEquals(InvocationStatus.UNKNOWN, outcome.status)
        assertTrue(outcome.message.contains("may still be delivered"))
    }

    @Test
    fun `platform failure codes become reasons the model can relay`() {
        val radioOff = SmsSendResults.describe("+14155550100", "hi", SmsSendReport(parts = 1, failureCode = 2))
        assertEquals(InvocationStatus.FAILED, radioOff.status)
        assertEquals("The text to +14155550100 was not sent: the radio is off.", radioOff.message)
        assertEquals(
            "The text to +14155550100 was not sent: Android reported SMS error code 42.",
            SmsSendResults.describe("+14155550100", "hi", SmsSendReport(parts = 1, failureCode = 42)).message,
        )
    }
}

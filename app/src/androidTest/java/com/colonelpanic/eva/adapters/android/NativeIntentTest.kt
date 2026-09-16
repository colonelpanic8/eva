package com.colonelpanic.eva.adapters.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIntentTest {
    @Test
    fun messageUsesSendToAndKeepsBodyOutsideRecipientUri() {
        val recipient = "+1 (202) 555-0100"
        val message = "Hello: bring café & tea?\nLine 2"
        val intent = MessageIntentBackend.messageIntent(listOf(recipient), message)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals(recipient, intent.data?.schemeSpecificPart)
        assertEquals(message, intent.getStringExtra("sms_body"))
        assertEquals(setOf("sms_body"), intent.extras?.keySet())
        assertNull(intent.component)
        assertEquals(0, intent.flags)
    }

    @Test
    fun groupMessageJoinsOnlyTheSeparatorEvaWrites() {
        val intent = MessageIntentBackend.messageIntent(listOf("+12025550100", "202 555-0101"), "Hi")
        assertEquals("smsto", intent.data?.scheme)
        assertEquals("+12025550100;202 555-0101", intent.data?.schemeSpecificPart)
        assertEquals(1, intent.data?.encodedSchemeSpecificPart?.count { it == ';' })
        assertEquals("Hi", intent.getStringExtra("sms_body"))
    }
}

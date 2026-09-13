package com.colonelpanic.eva.adapters.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
class NativeIntentTest {
    @Test
    fun navigationKeepsDestinationSeparateFromTravelMode() {
        val destination = "Park &mode=w # café"
        val intent = NavigationIntentBackend.navigationIntent(destination)
        val data = checkNotNull(intent.data)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("google.navigation", data.scheme)
        val parts = data.encodedSchemeSpecificPart.split('&')
        assertEquals(2, parts.size)
        assertEquals(destination, URLDecoder.decode(parts[0].removePrefix("q="), "UTF-8"))
        assertEquals("mode=d", parts[1])
        assertNull(intent.extras)
        assertEquals(0, intent.flags)
    }

    @Test
    fun messageUsesSendToAndKeepsBodyOutsideRecipientUri() {
        val recipient = "+1 (202) 555-0100"
        val message = "Hello: bring café & tea?\nLine 2"
        val intent = MessageIntentBackend.messageIntent(recipient, message)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals(recipient, intent.data?.schemeSpecificPart)
        assertEquals(message, intent.getStringExtra("sms_body"))
        assertEquals(setOf("sms_body"), intent.extras?.keySet())
        assertNull(intent.component)
        assertEquals(0, intent.flags)
    }
}

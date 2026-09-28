package com.colonelpanic.eva.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class MessagingSettingsTest {
    @Test fun defaultsAreOffAndApprovedIdentitiesPersist() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("eva.messaging", 0)
            .edit()
            .clear()
            .commit()
        val settings = MessagingSettings(context)
        assertFalse(settings.state.value.enabled)
        assertTrue(
            settings.state.value.replies
                .isEmpty(),
        )
        settings.enable(true)
        settings.allowReply("uid:app:install:signer", true)
        val reopened = MessagingSettings(context)
        assertTrue(reopened.state.value.enabled)
        assertEquals(setOf("uid:app:install:signer"), reopened.state.value.replies)
        assertFalse("uid:app:reinstall:signer" in reopened.state.value.replies)
        reopened.allowReply("uid:app:install:signer", false)
        assertTrue(
            MessagingSettings(context)
                .state.value.replies
                .isEmpty(),
        )
    }

    @Test fun retiredBridgesStayListedUntilDismissedAndTheirTokensAreCleared() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("eva.messaging", 0)
            .edit()
            .clear()
            .putString("bridges", """{"whatsapp":{"label":"WhatsApp","origin":"https://bridge.example.ts.net"}}""")
            .commit()
        val cleared = mutableListOf<String>()
        val secrets =
            object : SecretStore(context) {
                override fun clear(name: String) {
                    cleared += name
                }
            }
        val settings = MessagingSettings(context, secrets = secrets)
        assertEquals(
            "https://bridge.example.ts.net",
            settings.legacyBridges.value
                .getValue("whatsapp")
                .origin,
        )
        settings.enable(true)
        assertEquals(setOf("whatsapp"), MessagingSettings(context, secrets = secrets).legacyBridges.value.keys)
        settings.dismissLegacyBridges()
        assertEquals(listOf("messaging:whatsapp:bearer"), cleared)
        assertTrue(MessagingSettings(context, secrets = secrets).legacyBridges.value.isEmpty())
        assertTrue(MessagingSettings(context, secrets = secrets).state.value.enabled)
    }
}

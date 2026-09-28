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

    @Test fun bridgesPersistPortablyWhileTokensStayScopedToTheirOrigin() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("eva.messaging", 0)
            .edit()
            .clear()
            .commit()
        val secrets =
            object : SecretStore(context) {
                val values = mutableMapOf<String, String>()

                override fun read(name: String) = values[name]

                override fun write(
                    name: String,
                    value: String,
                ) {
                    values[name] = value
                }

                override fun clear(name: String) {
                    values.remove(name)
                }
            }
        val settings = MessagingSettings(context, secrets = secrets)
        val origin = "https://bridge.example.ts.net"
        settings.saveBridge("whatsapp", "WhatsApp", "$origin/", "device-token")
        assertEquals(
            "WhatsApp",
            settings.state.value.bridges
                .getValue("whatsapp")
                .label,
        )
        assertEquals(
            origin,
            settings.state.value.bridges
                .getValue("whatsapp")
                .origin,
        )
        assertEquals("Bearer device-token", settings.bridgeCredential("whatsapp")?.authorization())
        assertTrue(settings.missingBridgeCredentials(settings.state.value.bridges).isEmpty())
        assertFalse(
            context
                .getSharedPreferences("eva.messaging", 0)
                .all
                .toString()
                .contains("device-token"),
        )

        val reopened = MessagingSettings(context, secrets = secrets)
        assertEquals(settings.state.value.bridges, reopened.state.value.bridges)
        reopened.saveBridge("whatsapp", "WhatsApp", "https://moved.example.ts.net", "")
        assertEquals(null, reopened.bridgeCredential("whatsapp"))
        assertEquals(listOf("messaging/whatsapp/bearer"), reopened.missingBridgeCredentials(reopened.state.value.bridges))
        // A token entered for an unchanged bridge leaves the portable state equal, so presence is signalled separately.
        val before = reopened.state.value to reopened.credentialRevision.value
        reopened.saveBridge("whatsapp", "WhatsApp", "https://moved.example.ts.net", "new-token")
        assertEquals("Bearer new-token", reopened.bridgeCredential("whatsapp")?.authorization())
        assertEquals(before.first, reopened.state.value)
        assertTrue(reopened.credentialRevision.value != before.second)
        assertTrue(reopened.missingBridgeCredentials(reopened.state.value.bridges).isEmpty())

        // A restore replaces the portable part and leaves the device-local token alone.
        reopened.replace(reopened.state.value.copy(bridges = emptyMap()))
        assertEquals(null, reopened.bridgeCredential("whatsapp"))
        val moved =
            com.colonelpanic.eva.data.configuration
                .MessagingBridgeDefinition("WhatsApp", "https://moved.example.ts.net")
        reopened.replace(reopened.state.value.copy(bridges = mapOf("whatsapp" to moved)))
        assertEquals("Bearer new-token", reopened.bridgeCredential("whatsapp")?.authorization())
        reopened.removeBridge("whatsapp")
        assertTrue(
            MessagingSettings(context)
                .state.value.bridges
                .isEmpty(),
        )
        listOf("sms", "notifications", "WhatsApp", "what sapp").forEach { name ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { reopened.saveBridge(name, "x", origin, "t") }
        }
        org.junit.Assert.assertThrows(
            IllegalArgumentException::class.java,
        ) { reopened.saveBridge("signal", "x", "http://bridge.example", "t") }
        assertTrue(
            MessagingSettings(context)
                .state.value.bridges
                .isEmpty(),
        )
    }
}

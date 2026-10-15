package com.colonelpanic.eva.data

import android.app.Application
import android.content.Context
import com.colonelpanic.eva.data.configuration.TwilioCallsConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class TwilioCallSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val configuration = TwilioCallsConfiguration("AC" + "a".repeat(32), "AP" + "c".repeat(32), "+15592493930")
    private val keySid = "SK" + "b".repeat(32)

    private class FakeSecrets(
        context: Context,
    ) : SecretStore(context) {
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

    @Test fun theKeyStaysInTheSecretStoreAndABlankEntryKeepsIt() {
        val secrets = FakeSecrets(context)
        val changed = mutableListOf<String>()
        val settings = TwilioCallSettings(context, onCredentialChanged = { changed += it }, secrets = secrets)
        settings.save(configuration, "", "")
        assertEquals(configuration, settings.configuration())
        assertNull("A route saved without a key is reported as needing one.", settings.apiKey())

        settings.save(configuration, keySid, "test-secret")
        assertEquals(keySid, settings.apiKey()!!.sid)
        assertEquals(listOf("provider/twilio-calls"), changed)
        val other = configuration.copy(callerId = "+15551234567")
        settings.save(other, " ", "")
        assertEquals("test-secret", settings.apiKey()!!.secret)
        assertEquals(other, TwilioCallSettings(context, secrets = secrets).configuration())
        val prefs =
            context
                .getSharedPreferences("eva.twilio-calls", 0)
                .all.values
                .joinToString()
        assertTrue("test-secret" !in prefs)

        assertEquals(
            "Enter both the API key SID and its secret.",
            runCatching { settings.save(configuration, keySid, "") }.exceptionOrNull()!!.message,
        )
        settings.remove()
        assertNull(settings.configuration())
        assertTrue(secrets.values.isEmpty())
    }
}

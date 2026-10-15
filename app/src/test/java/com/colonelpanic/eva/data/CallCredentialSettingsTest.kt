package com.colonelpanic.eva.data

import android.app.Application
import android.content.Context
import com.colonelpanic.eva.call.CallCredential
import com.colonelpanic.eva.call.CallCredentialUnavailable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class CallCredentialSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val secrets by lazy { FakeSecrets(context) }

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

    @Before fun clear() {
        context
            .getSharedPreferences("eva.call-credentials", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test fun referencesPersistWhileValuesStayInTheSecretStore() =
        runBlocking {
            val changed = mutableListOf<String>()
            val settings = CallCredentialSettings(context, onCredentialChanged = { changed += it }, secrets = secrets)
            settings.save("bank/pin", " Bank PIN ", "4417 3902 1886 65#")
            assertEquals("44173902188665#", String(settings.resolve("bank/pin")))
            assertEquals(listOf("call/bank/pin"), changed)

            val reopened = CallCredentialSettings(context, secrets = secrets)
            assertEquals(listOf(CallCredential("bank/pin", "Bank PIN")), reopened.configured())
            assertTrue(reopened.missingValues().isEmpty())
            val stored =
                context
                    .getSharedPreferences("eva.call-credentials", 0)
                    .all
                    .toString()
            assertFalse(stored, stored.contains("44173902188665"))

            // A blank value keeps the saved one while the label changes.
            reopened.save("bank/pin", "Bank card PIN", "")
            assertEquals("44173902188665#", String(reopened.resolve("bank/pin")))
            reopened.remove("bank/pin")
            assertTrue(reopened.configured().isEmpty())
            assertTrue(secrets.values.isEmpty())
        }

    @Test fun savingRejectsAnythingAKeypadCannotSend() {
        val settings = CallCredentialSettings(context, secrets = secrets)
        assertThrows(IllegalArgumentException::class.java) { settings.save("bank/pin", "Bank PIN", "4417-3902") }
        assertThrows(IllegalArgumentException::class.java) { settings.save("Bank PIN", "Bank PIN", "4417") }
        assertThrows(IllegalArgumentException::class.java) { settings.save("bank/pin", " ", "4417") }
        assertTrue(settings.configured().isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test fun resolvingSaysWhatIsMissingAndWhereToSetIt() =
        runBlocking {
            val settings = CallCredentialSettings(context, secrets = secrets)
            val unknown = assertThrows(CallCredentialUnavailable::class.java) { runBlocking { settings.resolve("bank/pin") } }
            assertEquals(
                "No call credential is saved as bank/pin; none are saved. The owner adds it under Messaging > Call keypad credentials.",
                unknown.message,
            )

            // A restored reference arrives without its value.
            settings.replace(mapOf("comcast/account" to "Comcast account number"))
            assertEquals(setOf("comcast/account"), settings.missingValues())
            val missing = assertThrows(CallCredentialUnavailable::class.java) { runBlocking { settings.resolve("comcast/account") } }
            assertEquals(
                "Call credential comcast/account (Comcast account number) has no value on this phone. The owner enters it " +
                    "under Messaging > Call keypad credentials.",
                missing.message,
            )

            secrets.values["call:comcast:account"] = "44173902188x65"
            val invalid = assertThrows(CallCredentialUnavailable::class.java) { runBlocking { settings.resolve("comcast/account") } }
            assertFalse(invalid.message!!.contains("4417390218"))
            assertTrue(invalid.message!!.contains("not digits, * and # only"))
        }
}

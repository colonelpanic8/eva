package com.colonelpanic.eva.data

import android.app.Application
import android.content.Context
import com.colonelpanic.eva.capability.extensions.CredentialSpec
import com.colonelpanic.eva.capability.extensions.CredentialValues
import com.colonelpanic.eva.capability.extensions.ExtensionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class ExtensionCredentialSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val secrets by lazy { FakeSecrets(context) }
    private val vault = ExtensionIdentity(0, "org.example.vault", "org.example.vault/.Extension", "signer-a", 10001, 1)
    private val passphrase = CredentialSpec("passphrase", "Key passphrase", "Unlocks the signing key.")
    private val pin = CredentialSpec("pin", "PIN", "Unlocks the app.")

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
            .getSharedPreferences("eva.extension-credentials", 0)
            .edit()
            .clear()
            .commit()
    }

    private fun values(
        settings: ExtensionCredentialSettings,
        identity: ExtensionIdentity,
        declared: List<CredentialSpec>,
    ) = (settings.values(identity, declared) as CredentialValues.Ready).values

    @Test fun onlyDeclaredNamesWithValuesAreDeliveredAndOnlyToTheirPackage() {
        val changed = mutableListOf<String>()
        val settings = ExtensionCredentialSettings(context, { changed += it }, secrets)
        assertNull(settings.save(vault, passphrase, "correct horse"))
        assertNull(settings.save(vault, CredentialSpec("retired", "Old", "No longer declared."), "stale"))

        assertEquals(mapOf("passphrase" to "correct horse"), values(settings, vault, listOf(passphrase, pin)))
        assertEquals(emptyMap<String, String>(), values(settings, vault.copy(packageName = "org.example.other"), listOf(passphrase)))
        assertEquals(ExtensionCredentialSettings.Status.NOT_SET, settings.status(vault, "pin"))
        assertEquals(listOf("extension/org.example.vault/passphrase", "extension/org.example.vault/retired"), changed)
        assertEquals(setOf("extension/org.example.vault/passphrase", "extension/org.example.vault/retired"), settings.saved())
        val stored = context.getSharedPreferences("eva.extension-credentials", 0).all.toString()
        assertFalse(stored, stored.contains("correct horse"))

        settings.clear("org.example.vault", "passphrase")
        assertEquals(emptyMap<String, String>(), values(settings, vault, listOf(passphrase)))
        assertFalse(settings.hasValue("extension/org.example.vault/passphrase"))
    }

    @Test fun aValueSavedForAnotherSignerOrUserIsRefusedUntilReentered() {
        val settings = ExtensionCredentialSettings(context, secrets = secrets)
        settings.save(vault, passphrase, "correct horse")
        val reinstalled = vault.copy(signer = "signer-b", installedAt = 2)

        val refused = settings.values(reinstalled, listOf(passphrase))

        assertTrue(refused is CredentialValues.Refused)
        assertTrue((refused as CredentialValues.Refused).message.contains("Re-enter it"))
        assertFalse(refused.message.contains("correct horse"))
        assertTrue(settings.values(vault.copy(user = 10), listOf(passphrase)) is CredentialValues.Refused)
        assertEquals(ExtensionCredentialSettings.Status.OTHER_SIGNER, settings.status(reinstalled, "passphrase"))
        assertEquals(
            mapOf("passphrase" to "correct horse"),
            values(settings, vault.copy(installedAt = 3), listOf(passphrase)),
        )

        settings.save(reinstalled, passphrase, "new horse")
        assertEquals(mapOf("passphrase" to "new horse"), values(settings, reinstalled, listOf(passphrase)))
        assertTrue(settings.values(vault, listOf(passphrase)) is CredentialValues.Refused)
    }

    @Test fun emptyAndOversizedValuesAreRefusedWithAReason() {
        val settings = ExtensionCredentialSettings(context, secrets = secrets)
        assertTrue(settings.save(vault, passphrase, "")!!.contains("Clear"))
        assertTrue(settings.save(vault, passphrase, "x".repeat(4097))!!.contains("4096"))
        assertTrue(settings.saved().isEmpty())
    }
}

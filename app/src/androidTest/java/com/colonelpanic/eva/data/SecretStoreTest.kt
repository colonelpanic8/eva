package com.colonelpanic.eva.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun secretsRoundTripThroughTheKeystoreAndNeverRestAsPlaintext() {
        val store = SecretStore(context)
        store.clear("test.secret")
        assertNull(store.read("test.secret"))
        store.write("test.secret", "sk-live-example-1234567890")
        assertEquals("sk-live-example-1234567890", SecretStore(context).read("test.secret"))
        val raw = context.getSharedPreferences("eva.secrets", 0).getString("test.secret", null)
        assertTrue(!raw.isNullOrBlank())
        assertFalse(raw!!.contains("sk-live"))
        store.clear("test.secret")
        assertNull(store.read("test.secret"))
    }

    @Test
    fun settingsReportKeyPresenceAndRejectImplausibleKeys() {
        val settings = OpenAiSettings(context)
        settings.clearApiKey()
        assertFalse(settings.hasApiKey.value)
        runCatching { settings.saveApiKey("short") }.also { assertTrue(it.isFailure) }
        assertFalse(settings.hasApiKey.value)
        settings.saveApiKey("  sk-test-abcdefghijklmnopqrstuvwxyz  ")
        assertTrue(settings.hasApiKey.value)
        assertEquals("sk-test-abcdefghijklmnopqrstuvwxyz", settings.requireApiKey())
        settings.clearApiKey()
        assertFalse(settings.hasApiKey.value)
    }

    @Test
    fun chosenModelsPersistAndBlankRestoresTheDefault() {
        val settings = OpenAiSettings(context)
        settings.saveTextModel("")
        val default = settings.textModel
        settings.saveTextModel("gpt-5.6-luna")
        assertEquals("gpt-5.6-luna", settings.textModel)
        assertEquals("gpt-5.6-luna", settings.textModelFlow.value)
        assertEquals("gpt-5.6-luna", OpenAiSettings(context).textModel)
        runCatching { settings.saveTextModel("not a model") }.also { assertTrue(it.isFailure) }
        assertEquals("gpt-5.6-luna", settings.textModel)
        settings.saveTextModel("")
        assertEquals(default, settings.textModel)
        assertEquals(default, OpenAiSettings(context).textModel)
    }
}

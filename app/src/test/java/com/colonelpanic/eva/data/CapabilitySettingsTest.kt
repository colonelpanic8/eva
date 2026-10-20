package com.colonelpanic.eva.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class CapabilitySettingsTest {
    @Test fun obsoleteSavedDeviceTaskLimitsDoNotBreakConfigurationRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eva.settings", 0)
        prefs
            .edit()
            .putString(
                "capabilities.deviceTask",
                """{"model":"saved-worker","maxSteps":17,"maxMillis":90000,"modelTimeoutMillis":120000,"maxRefusals":4,"maxScreenshots":3}""",
            ).commit()
        val settings = CapabilitySettings(context)
        val expected =
            com.colonelpanic.eva.data.configuration
                .DeviceTaskConfiguration(model = "saved-worker")
        assertEquals(expected, settings.deviceTask)
        settings.saveDeviceTask(settings.deviceTask)
        assertEquals(expected, CapabilitySettings(context).deviceTask)
        val saved = prefs.getString("capabilities.deviceTask", "")!!
        listOf("maxSteps", "maxMillis", "modelTimeoutMillis", "maxRefusals", "maxScreenshots").forEach {
            assertFalse(saved.contains(it))
        }
    }

    @Test fun unsupportedSavedResearchEffortPreservesChoicesAndWarnsUntilSaved() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences(
                "eva.settings",
                0,
            ).edit()
            .putString(
                "capabilities.webResearch",
                """{"enabled":false,"model":"saved-model","effort":"minimal","timeoutSeconds":30}""",
            ).commit()
        val settings = CapabilitySettings(context)
        assertFalse(settings.webResearch.enabled)
        assertEquals("saved-model", settings.webResearch.model)
        assertEquals(30, settings.webResearch.timeoutSeconds)
        assertEquals("low", settings.webResearch.effort)
        assertTrue(settings.webResearchNoticeFlow.value!!.contains("replaced with low"))
        settings.saveWebResearch(settings.webResearch.copy(effort = "medium"))
        assertNull(settings.webResearchNoticeFlow.value)
        val reopened = CapabilitySettings(context)
        assertEquals(settings.webResearch, reopened.webResearch)
        assertNull(reopened.webResearchNoticeFlow.value)
    }
}

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

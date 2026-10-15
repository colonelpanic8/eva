package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.providers.grok.GrokVoiceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class VoiceProviderSettingsTest {
    @Test fun `selecting Grok persists independently and leaves the existing OpenAI models untouched`() {
        val context = RuntimeEnvironment.getApplication()
        val openai = OpenAiSettings(context)
        openai.saveRealtimeModel("existing-voice-model")
        openai.saveTextModel("existing-text-model")
        var changes = 0
        val settings = VoiceProviderSettings(context, onChanged = { changes++ })
        assertEquals("openai", settings.state.value.provider)
        val choice = VoiceProviderSettings.Selection("grok", GrokVoiceConfiguration("grok-voice-latest", "ara", "none"))
        settings.save(choice)
        assertEquals(choice, VoiceProviderSettings(context).state.value)
        assertEquals(1, changes)
        assertFalse(settings.hasApiKey.value)
        assertEquals("existing-voice-model", openai.realtimeModel)
        assertEquals("existing-text-model", openai.textModel)
        settings.save(choice.copy(provider = "openai"))
        assertEquals(choice.grok, VoiceProviderSettings(context).state.value.grok)
    }
}

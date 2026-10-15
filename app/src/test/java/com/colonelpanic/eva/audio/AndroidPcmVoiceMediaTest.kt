package com.colonelpanic.eva.audio

import android.app.Application
import com.colonelpanic.eva.providers.grok.GrokVoiceConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AndroidPcmVoiceMediaTest {
    @Test fun `missing microphone permission fails visibly without reporting transport or playback evidence`() =
        runTest {
            val media = AndroidPcmVoiceMedia(RuntimeEnvironment.getApplication(), GrokVoiceConfiguration())
            val error = runCatching { media.start { error("No microphone samples expected") } }.exceptionOrNull()
            assertTrue(error is MediaException)
            assertEquals(MediaFailure.MicrophonePermissionRequired, (error as MediaException).failure)
            assertEquals(RealtimeMediaState.Failed(MediaFailure.MicrophonePermissionRequired), media.state.value)
            assertEquals(AudioFocusState.NONE, media.controls.value.focus)
            assertEquals(MediaTimeline(), media.timeline.value)
            media.close()
            media.close()
            assertFalse(media.state.value is RealtimeMediaState.Connected)
        }
}

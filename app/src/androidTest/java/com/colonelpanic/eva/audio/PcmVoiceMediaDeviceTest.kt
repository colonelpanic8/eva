package com.colonelpanic.eva.audio

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.providers.grok.GrokVoiceConfiguration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PcmVoiceMediaDeviceTest {
    @Test
    fun capturePlaybackMuteAndInterruptionUseAndroidAudioDevices() {
        assumeTrue("Dedicated emulator test", Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, MicrophonePermission.PERMISSION)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val media = AndroidPcmVoiceMedia(context, GrokVoiceConfiguration())
                val captured = Channel<ByteArray>(Channel.CONFLATED)
                val played = Channel<PcmVoiceMedia.Playback>(Channel.UNLIMITED)
                val collector = launch { media.playback.collect { event -> played.send(event) } }

                suspend fun finished(id: String): PcmVoiceMedia.Playback.Finished =
                    withTimeout(10_000) {
                        var event = played.receive()
                        while (event !is PcmVoiceMedia.Playback.Finished || event.responseId != id) {
                            if (event is PcmVoiceMedia.Playback.Failed) error(event.message)
                            event = played.receive()
                        }
                        event
                    }
                try {
                    media.start { buffer -> captured.trySend(buffer) }
                    assertEquals(RealtimeMediaState.Connected(false), media.state.value)
                    assertEquals(AudioFocusState.HELD, media.controls.value.focus)
                    assertTrue(withTimeout(10_000) { captured.receive() }.isNotEmpty())
                    assertEquals(MediaTimeline(), media.timeline.value)

                    media.enqueue("audible", tone(500))
                    media.finish("audible")
                    assertTrue(finished("audible").played)
                    assertEquals(RealtimeMediaState.Connected(true), media.state.value)

                    media.setMicrophoneMuted(true)
                    media.setPlaybackMuted(true)
                    delay(100)
                    assertTrue(withTimeout(10_000) { captured.receive() }.all { sample -> sample == 0.toByte() })
                    media.enqueue("muted", tone(250))
                    media.finish("muted")
                    assertFalse(finished("muted").played)

                    media.setPlaybackMuted(false)
                    media.enqueue("interrupted", tone(5000))
                    media.finish("interrupted")
                    withTimeout(10_000) {
                        while (played.receive() != PcmVoiceMedia.Playback.Started("interrupted")) Unit
                    }
                    delay(100)
                    assertTrue(media.interrupt().getValue("interrupted") in 0L until 5000L)
                    assertFalse(finished("interrupted").played)
                    media.enqueue("after-interruption", tone(250))
                    media.finish("after-interruption")
                    assertTrue(finished("after-interruption").played)

                    media.enqueue("closed-before-drain", tone(5000))
                    media.close()
                    assertFalse(finished("closed-before-drain").played)
                    media.close()
                    assertEquals(RealtimeMediaState.Closed, media.state.value)
                    assertEquals(AudioFocusState.NONE, media.controls.value.focus)
                } finally {
                    media.close()
                    collector.cancel()
                    captured.close()
                    played.close()
                }
            }
        }
    }

    private fun tone(millis: Int): ByteArray =
        ByteArray(24 * millis * 2).also { bytes ->
            repeat(bytes.size / 2) { frame ->
                val value = (sin(2 * Math.PI * 440 * frame / 24000) * 2000).toInt()
                bytes[frame * 2] = value.toByte()
                bytes[frame * 2 + 1] = (value shr 8).toByte()
            }
        }
}

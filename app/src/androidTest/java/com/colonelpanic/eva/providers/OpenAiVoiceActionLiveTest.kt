package com.colonelpanic.eva.providers

import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.audio.AndroidAudioRoute
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.audio.PeerLinkFactory
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.RealtimeMediaController
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.audio.webrtc.WebRtcPeerLink
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderSessionController
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.OpenAiRealtimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in live test of the workstation-free voice path: the phone opens its own
 * OpenAI Realtime session with an API key supplied as an instrumentation argument,
 * synthetic speech asks for a timer, and the real backend hands off to Clock.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiVoiceActionLiveTest {
    @Test
    fun spokenTimerRequestExecutesDirectlyAgainstOpenAi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val apiKey = args.getString("evaOpenAiKey")
        val path = args.getString("evaSpeechPcmPath")
        assumeTrue("Requires evaOpenAiKey and a synthetic PCM fixture", !apiKey.isNullOrBlank() && path != null)
        require(checkNotNull(path).matches(Regex("/data/local/tmp/eva-[a-z0-9-]+\\.pcm")))
        val fixture =
            instrumentation.uiAutomation.executeShellCommand("cat $path").use {
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(it)
                    .readBytes()
            }
        require(fixture.size in 96000..1920000 && fixture.size % 2 == 0)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, MicrophonePermission.PERMISSION)
        val app = context.applicationContext as EvaApplication
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking(Dispatchers.Main.immediate) {
                val armed = AtomicBoolean(false)
                var cursor = -96000
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                val module =
                    JavaAudioDeviceModule
                        .builder(context)
                        .setSampleRate(48000)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                        .setAudioBufferCallback { buffer, format, channels, rate, bytes, timestamp ->
                            check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && rate == 48000)
                            buffer.clear()
                            repeat(bytes) {
                                val sample =
                                    if (armed.get()) {
                                        val index = cursor++
                                        if (index in fixture.indices) fixture[index] else 0
                                    } else {
                                        0
                                    }
                                buffer.put(sample)
                            }
                            buffer.rewind()
                            timestamp
                        }.createAudioDeviceModule()
                val factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
                module.release()
                val repository = SqliteInvocationRepository(context)
                val audioManager = context.getSystemService(AudioManager::class.java)
                val controller =
                    ProviderSessionController(
                        registry = app.registry,
                        dispatcher = CapabilityDispatcher(app.registry, repository),
                        repository = repository,
                        scope = this,
                        providerFactory = { error("Typed path is not exercised here") },
                        mediaFactory = { mode ->
                            RealtimeMediaController(
                                RealtimeMediaConfig(mode),
                                PeerLinkFactory { linkMode -> WebRtcPeerLink(factory, linkMode) },
                                AndroidAudioRoute(audioManager),
                                { true },
                                this,
                            )
                        },
                        voiceProviderFactory = { _, audio -> OpenAiRealtimeProvider(ApiKeyAccess(checkNotNull(apiKey)), audio) },
                    )
                VoiceSessionService.start(context)
                try {
                    withTimeout(20_000) { controller.state.first { !it.isLoading } }
                    controller.connectVoice("", listenOnly = false)
                    withTimeout(70_000) {
                        controller.state.first { state ->
                            state.providerStatus == ProviderStatus.CONNECTED && state.mediaState is RealtimeMediaState.Connected
                        }
                    }
                    armed.set(true)
                    val handoff =
                        withTimeout(90_000) {
                            controller.state
                                .first { state ->
                                    state.entries.any { entry ->
                                        entry.capabilityId == CapabilityRegistry.SET_TIMER && entry.status == EntryStatus.HANDED_OFF
                                    }
                                }.entries
                                .first { it.capabilityId == CapabilityRegistry.SET_TIMER }
                        }
                    val record = repository.history().first { it.capabilityId == CapabilityRegistry.SET_TIMER }
                    assertEquals(InvocationStatus.HANDED_OFF, record.status)
                    val spoken =
                        withTimeout(60_000) {
                            controller.state
                                .first { state ->
                                    state.entries.any { entry ->
                                        entry.status == EntryStatus.ANSWER && entry.request.isEmpty() &&
                                            entry.response.contains("timer", ignoreCase = true)
                                    }
                                }.entries
                                .last { it.status == EntryStatus.ANSWER && it.response.contains("timer", ignoreCase = true) }
                        }
                    delay(5_000)
                    assertEquals(ProviderStatus.CONNECTED, controller.state.value.providerStatus)
                    assertTrue(controller.state.value.mediaState is RealtimeMediaState.Connected)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "evaDirectVoiceEvidence",
                                "PASS direct OpenAI session, spoken request, timer handoff, spoken confirmation",
                            )
                            putString("journalRequest", record.request)
                            putString("handoffMessage", handoff.response)
                            putString("spokenConfirmation", spoken.response)
                            putString(
                                "model",
                                controller.state.value.providerModel
                                    .orEmpty(),
                            )
                        },
                    )
                } finally {
                    armed.set(false)
                    controller.disconnect()
                    VoiceSessionService.stop(context)
                    factory.dispose()
                }
            }
        }
    }
}

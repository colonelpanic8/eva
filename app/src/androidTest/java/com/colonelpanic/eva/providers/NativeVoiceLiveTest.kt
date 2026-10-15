package com.colonelpanic.eva.providers

import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
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
import com.colonelpanic.eva.audio.webrtc.WebRtcPeerLink
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import com.colonelpanic.eva.providers.openai.OpenAiLiveProvider
import com.colonelpanic.eva.providers.openai.OpenAiRealtimeProvider
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live test. All captured samples are replaced with a generated speech fixture. */
@RunWith(AndroidJUnit4::class)
class NativeVoiceLiveTest {
    @Test
    fun syntheticSpeechReachesProviderAndReturnsDecodedAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val link = args.getString("evaBrokerLink")
        val subscription = args.getString("evaSubscriptionVoice") == "true"
        val liveApi = args.getString("evaLiveVoice") == "true"
        val liveSubscription = args.getString("evaLiveSubscriptionVoice") == "true"
        val live = liveApi || liveSubscription
        val path = args.getString("evaSpeechPcmPath")
        assumeTrue("Requires an explicit voice route and synthetic PCM fixture", (link != null || subscription || live) && path != null)
        require(listOf(link != null, subscription, liveApi, liveSubscription).count { it } == 1)
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
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val armed = AtomicBoolean(false)
                val sentBytes = AtomicInteger()
                val receivedNonzeroBytes = AtomicInteger()
                val formatValid = AtomicBoolean(true)
                var cursor = -96000
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                val module =
                    JavaAudioDeviceModule
                        .builder(context)
                        .setSampleRate(48000)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                        .setAudioBufferCallback { buffer, format, channels, rate, bytes, timestamp ->
                            if (format != AudioFormat.ENCODING_PCM_16BIT || channels != 1 || rate != 48000) formatValid.set(false)
                            buffer.clear()
                            repeat(bytes) {
                                val sample =
                                    if (armed.get()) {
                                        val index = cursor++
                                        if (index in fixture.indices) {
                                            sentBytes.incrementAndGet()
                                            fixture[index]
                                        } else {
                                            0
                                        }
                                    } else {
                                        0
                                    }
                                buffer.put(sample)
                            }
                            buffer.rewind()
                            timestamp
                        }.setPlaybackSamplesReadyCallback { samples ->
                            receivedNonzeroBytes.addAndGet(samples.data.count { byte -> byte != 0.toByte() })
                        }.createAudioDeviceModule()
                val factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
                module.release()
                val media =
                    RealtimeMediaController(
                        RealtimeMediaConfig(),
                        PeerLinkFactory { WebRtcPeerLink(factory) },
                        AndroidAudioRoute(context.getSystemService(AudioManager::class.java)),
                        { true },
                        this,
                    )
                var session: ConversationSession? = null
                try {
                    val provider =
                        if (live) {
                            val application = context.applicationContext as EvaApplication
                            val access =
                                if (liveSubscription) {
                                    subscriptionAccess(args, application)
                                } else {
                                    ApiKeyAccess(args.getString("evaLiveApiKey") ?: application.settings.requireApiKey())
                                }
                            OpenAiLiveProvider(
                                access,
                                media,
                                "This is a synthetic audio test. Delegate arithmetic to the client. Client updates are numbered JSON envelopes whose text reconstructs an EVA note and result data. Explain the verified answer briefly.",
                            )
                        } else if (subscription) {
                            val account = (context.applicationContext as EvaApplication).chatGpt
                            check(account.signedIn) { "The debug app needs a ChatGPT sign-in for this test." }
                            OpenAiRealtimeProvider(SubscriptionAccess(account, "1.0.0"), media)
                        } else {
                            BrokerConversationProvider(
                                BrokerEndpoint.parse(checkNotNull(link)),
                                offerSdp = media.createOffer(),
                                onAnswer = media::acceptAnswer,
                            )
                        }
                    session =
                        provider.open(
                            SessionOpenRequest(
                                "Answer the user's arithmetic question briefly. Include EVA in the spoken answer. No phone actions are available.",
                                ProviderToolCatalog("voice-no-tools-v1", emptyList()),
                            ),
                        )
                    val heardQuestion = CompletableDeferred<Unit>()
                    val heardAnswer = CompletableDeferred<Unit>()
                    val liveDelegated = CompletableDeferred<Unit>()
                    val liveCaptions = mutableMapOf<String, String>()
                    val collector =
                        launch {
                            session.events.collect { event ->
                                when (event) {
                                    is ProviderEvent.Transcript -> {
                                        if (event.role == "user" &&
                                            Regex("(?i)(thirty.seven|37)").containsMatchIn(event.text)
                                        ) {
                                            heardQuestion.complete(Unit)
                                        }
                                        if (event.role == "assistant" && Regex("(?i)(ninety.five|95)").containsMatchIn(event.text)) {
                                            heardAnswer.complete(Unit)
                                        }
                                    }

                                    is ProviderEvent.TimedTranscript -> {
                                        val text = liveCaptions.getOrDefault(event.role, "") + event.text
                                        liveCaptions[event.role] = text
                                        if (event.role == "user" &&
                                            Regex("(?i)(thirty.seven|37)").containsMatchIn(text)
                                        ) {
                                            heardQuestion.complete(Unit)
                                        }
                                        if (event.role == "assistant" &&
                                            Regex("(?i)(ninety.five|95)").containsMatchIn(text)
                                        ) {
                                            heardAnswer.complete(Unit)
                                        }
                                    }

                                    is ProviderEvent.ClientDelegationRequested -> {
                                        liveDelegated.complete(Unit)
                                        (session as ClientDelegationSession).appendDelegationUpdate(
                                            event.delegationId,
                                            "Verified synthetic test calculation: EVA calculated 37 + 58 = ${37 + 58}. Tell the user the result.",
                                        )
                                    }

                                    is ProviderEvent.Failure -> {
                                        error(event.message)
                                    }

                                    is ProviderEvent.AssistantText -> {
                                        if (Regex("(?i)(ninety.five|95)").containsMatchIn(event.text)) heardAnswer.complete(Unit)
                                    }

                                    else -> {}
                                }
                            }
                        }
                    try {
                        val connection =
                            withTimeout(70000) {
                                media.state.first { state ->
                                    state is RealtimeMediaState.Connected || state is RealtimeMediaState.Failed
                                }
                            }
                        check(connection is RealtimeMediaState.Connected) { "Media failed: $connection" }
                        armed.set(true)
                        withTimeout(60000) {
                            heardQuestion.await()
                            heardAnswer.await()
                            if (live) liveDelegated.await()
                        }
                        withTimeout(10000) { while (receivedNonzeroBytes.get() == 0) delay(100) }
                        assertTrue("Audio callback must retain 48kHz mono PCM16", formatValid.get())
                        assertTrue("Synthetic speech was sent", sentBytes.get() >= fixture.size)
                        assertTrue("Provider audio decoded to nonzero PCM", receivedNonzeroBytes.get() > 0)
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "evaVoiceEvidence",
                                    "PASS synthetic PCM input, user transcript, arithmetic answer, nonzero decoded output",
                                )
                                putInt("syntheticBytes", sentBytes.get())
                                putInt("nonzeroDecodedBytes", receivedNonzeroBytes.get())
                                putString(
                                    "route",
                                    if (liveSubscription) {
                                        "direct-live-subscription"
                                    } else if (live) {
                                        "direct-live-api"
                                    } else if (subscription) {
                                        "direct-subscription"
                                    } else {
                                        "broker"
                                    },
                                )
                            },
                        )
                    } finally {
                        collector.cancelAndJoin()
                    }
                } finally {
                    armed.set(false)
                    session?.close()
                    media.close()
                    factory.dispose()
                }
            }
        }
    }

    private fun subscriptionAccess(
        args: Bundle,
        application: EvaApplication,
    ): SubscriptionAccess {
        val port = args.getString("credentialPort")?.toInt()
        if (port == null) {
            check(application.chatGpt.signedIn) { "The debug app needs a ChatGPT sign-in for this test." }
            return SubscriptionAccess(application.chatGpt, application.clientVersion.orEmpty())
        }
        require(Build.MODEL.startsWith("sdk_gphone")) { "The runtime credential bridge is emulator-only." }
        val credentials =
            OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port/credentials").build()).execute().use {
                check(it.isSuccessful) { "The runtime credential bridge rejected the request." }
                Json
                    .parseToJsonElement(it.body.string())
                    .jsonObject
                    .getValue("tokens")
                    .jsonObject
            }
        val tokens =
            ChatGptTokens(
                "",
                credentials.getValue("access_token").jsonPrimitive.content,
                "",
                credentials.getValue("account_id").jsonPrimitive.content,
                null,
                null,
                0,
            )
        return SubscriptionAccess({ tokens }, application.clientVersion.orEmpty())
    }
}

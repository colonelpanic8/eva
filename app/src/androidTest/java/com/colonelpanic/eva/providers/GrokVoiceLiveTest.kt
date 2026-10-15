package com.colonelpanic.eva.providers

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.audio.AndroidPcmVoiceMedia
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.audio.PcmVoiceMedia
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.SqliteConversationStore
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.data.VoiceProviderSettings
import com.colonelpanic.eva.providers.grok.GrokVoiceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in paid xAI session, with synthetic capture and real Android PCM playback. */
@RunWith(AndroidJUnit4::class)
class GrokVoiceLiveTest {
    @Test
    fun spokenRequestDispatchesToolAndPlaysItsAnswer() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires evaGrokLive=true", args.getString("evaGrokLive") == "true")
        assumeTrue("Dedicated emulator only", Build.MODEL.startsWith("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as EvaApplication
        args.getString("credentialPort")?.toInt()?.let { port ->
            require(port in 1024..65535)
            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/credentials").build()).execute().use {
                    check(it.isSuccessful) { "Could not provision the emulator's xAI credential." }
                    app.voiceProviders.saveApiKey(it.body.string().trim())
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
        app.voiceProviders.requireApiKey()
        app.voiceProviders.save(VoiceProviderSettings.Selection(provider = "grok"))
        val path = checkNotNull(args.getString("evaSpeechPcmPath"))
        require(path.matches(Regex("/data/local/tmp/eva-[a-z0-9-]+\\.pcm")))
        val fixture =
            instrumentation.uiAutomation.executeShellCommand("cat $path").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        require(fixture.size in 48000..960000 && fixture.size % 2 == 0)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, MicrophonePermission.PERMISSION)
        val dbName = "grok-live-${UUID.randomUUID()}.db"
        val journal = JournalDatabase(context, dbName)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking(Dispatchers.Main.immediate) {
                    val armed = AtomicBoolean(false)
                    val sentBytes = AtomicInteger()
                    val receivedBytes = AtomicLong()
                    val executions = AtomicInteger()
                    val playbackDrained = CompletableDeferred<Unit>()
                    val pcm = AndroidPcmVoiceMedia(context, app.voiceProviders.state.value.grok)
                    val media =
                        object : PcmVoiceMedia by pcm {
                            override val playback =
                                pcm.playback.onEach { event ->
                                    if (event is PcmVoiceMedia.Playback.Finished && event.played) playbackDrained.complete(Unit)
                                }
                            var cursor = -48000

                            override suspend fun start(onAudio: (ByteArray) -> Unit) =
                                pcm.start { buffer ->
                                    onAudio(
                                        ByteArray(buffer.size) {
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
                                        },
                                    )
                                }

                            override fun enqueue(
                                responseId: String,
                                audio: ByteArray,
                            ) {
                                receivedBytes.addAndGet(audio.size.toLong())
                                pcm.enqueue(responseId, audio)
                            }
                        }
                    val fact =
                        CapabilityDefinition(
                            "test.fact",
                            "Look up a stored fact",
                            "Look up the courier's name. This is the only way to learn the name.",
                            Json
                                .parseToJsonElement(
                                    """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
                                ).jsonObject,
                            readOnly = true,
                        )
                    val registry =
                        CapabilityRegistry(
                            mapOf(
                                fact.id to
                                    object : ExecutionBackend {
                                        override suspend fun unavailableReason(): String? = null

                                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                                            executions.incrementAndGet()
                                            return ExecutionOutcome(InvocationStatus.COMPLETED, "The courier's name is Wren.")
                                        }
                                    },
                            ),
                            listOf(fact),
                        )
                    val repository = SqliteInvocationRepository(journal)
                    val controller =
                        ThreadController(
                            registry = registry,
                            dispatcher = CapabilityDispatcher(registry, repository),
                            repository = repository,
                            store = SqliteConversationStore(journal),
                            scope = this,
                            providerFactory = { error("This probe has no background model credential.") },
                            mediaFactory = { media },
                            voiceProviderFactory = { _, audio ->
                                GrokVoiceProvider(
                                    app.voiceProviders.requireApiKey(),
                                    requireNotNull(audio as? PcmVoiceMedia) { "Grok live test requires PCM media." },
                                    pcm.configuration,
                                )
                            },
                            quietHangUpMillis = { 0 },
                            prompt = {
                                PromptConfig(
                                    listOf(
                                        PromptComponent(
                                            id = "grok-live-test",
                                            instruction =
                                                "When asked about the courier, call test.fact " +
                                                    "and speak the returned name in one short sentence.",
                                        ),
                                    ),
                                )
                            },
                        )
                    VoiceSessionService.start(context)
                    try {
                        withTimeout(120_000) {
                            controller.state.first { !it.isLoading }
                            controller.connectVoice("")
                            val connected =
                                controller.state.first {
                                    it.providerStatus != ProviderStatus.CONNECTING ||
                                        it.errorMessage != null
                                }
                            check(connected.providerStatus == ProviderStatus.CONNECTED) {
                                connected.errorMessage ?: connected.providerMessage.orEmpty()
                            }
                            armed.set(true)
                            val answered =
                                controller.state.first { state ->
                                    state.errorMessage != null || state.providerStatus != ProviderStatus.CONNECTED ||
                                        state.entries.any { entry ->
                                            entry.status == EntryStatus.ANSWER &&
                                                entry.response.contains("Wren", true)
                                        }
                                }
                            check(answered.errorMessage == null) { answered.errorMessage.orEmpty() }
                            check(answered.providerStatus == ProviderStatus.CONNECTED) { answered.providerMessage.orEmpty() }
                            playbackDrained.await()
                            assertEquals(1, executions.get())
                            assertEquals(InvocationStatus.COMPLETED, repository.history().single { it.capabilityId == fact.id }.status)
                            assertTrue(answered.entries.any { it.request.contains("courier", true) })
                            assertEquals(fixture.size, sentBytes.get())
                            assertTrue(receivedBytes.get() > 0)
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "grokVoiceEvidence",
                                        "PASS xAI session, synthetic speech, transcript, dispatcher receipt, answer and Android playback drain",
                                    )
                                    putLong("receivedPcmBytes", receivedBytes.get())
                                    putInt("sentPcmBytes", sentBytes.get())
                                    putInt("executions", executions.get())
                                },
                            )
                        }
                    } finally {
                        armed.set(false)
                        controller.disconnect()
                        pcm.close()
                        VoiceSessionService.stop(context)
                    }
                }
            }
        } finally {
            journal.close()
            context.deleteDatabase(dbName)
        }
    }
}

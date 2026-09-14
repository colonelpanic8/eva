package com.colonelpanic.eva

import android.app.Application
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.adapters.android.AppFunctionsBackend
import com.colonelpanic.eva.adapters.android.ContactHistory
import com.colonelpanic.eva.adapters.android.ContactNameKeywords
import com.colonelpanic.eva.adapters.android.ContactsQueryBackend
import com.colonelpanic.eva.adapters.android.IntentBackend
import com.colonelpanic.eva.adapters.android.MapIntentBackend
import com.colonelpanic.eva.adapters.android.MessageIntentBackend
import com.colonelpanic.eva.adapters.android.MessageTargets
import com.colonelpanic.eva.adapters.android.MessagingReadBackend
import com.colonelpanic.eva.adapters.android.MessagingStore
import com.colonelpanic.eva.adapters.android.NativeIntents
import com.colonelpanic.eva.adapters.android.NavigationIntentBackend
import com.colonelpanic.eva.adapters.android.ShizukuShellHost
import com.colonelpanic.eva.adapters.android.SmsSendBackend
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.VoiceSessionHost
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.audio.VoiceSessionStatus
import com.colonelpanic.eva.audio.webrtc.WebRtcMediaSessionFactory
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.conversation.ProviderSessionController
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.data.AppearanceSettings
import com.colonelpanic.eva.data.ChatGptAccountStore
import com.colonelpanic.eva.data.ChosenNumbers
import com.colonelpanic.eva.data.OpenAiSettings
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.providers.BrokerConversationProvider
import com.colonelpanic.eva.providers.BrokerEndpoint
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.ChatGptSignIn
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.providers.openai.OpenAiAccess
import com.colonelpanic.eva.providers.openai.OpenAiModelCatalog
import com.colonelpanic.eva.providers.openai.OpenAiRealtimeProvider
import com.colonelpanic.eva.providers.openai.OpenAiResponsesProvider
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EvaApplication :
    Application(),
    VoiceSessionHost {
    val intentHost = AndroidIntentHost()
    val shizukuShellHost by lazy { if (Build.VERSION.SDK_INT >= 37) ShizukuShellHost(this) else null }

    private fun intent(
        success: String,
        missing: String,
        build: (Map<String, String>) -> android.content.Intent?,
    ) = IntentBackend(intentHost, success, missing, build)

    private val messagingStore by lazy { MessagingStore(this) }
    private val messageTargets by lazy { MessageTargets(intentHost, messagingStore) }
    private val chosenNumbers by lazy { ChosenNumbers(this) }

    private suspend fun contactHistory() = ContactHistory(messagingStore.lastMessaged(), chosenNumbers.all())

    private val mediaFactory by lazy { WebRtcMediaSessionFactory(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings by lazy { OpenAiSettings(this) }
    val appearance by lazy { AppearanceSettings(this) }
    val chatGpt by lazy { ChatGptAccountStore(this) }
    val signIn by lazy { ChatGptSignIn(save = chatGpt::save) }
    private var signInJob: Job? = null
    private val catalog = OpenAiModelCatalog()
    private val mutableModels = MutableStateFlow<Map<ModelKind, List<String>>>(emptyMap())

    /** Models this account can use, empty until credentials are present and the list loads. */
    val availableModels = mutableModels.asStateFlow()
    private val mutableVoiceSession = MutableStateFlow(VoiceSessionStatus())

    /** Mirrors the live session so the notification can render and control it without the activity. */
    override val voiceSession = mutableVoiceSession.asStateFlow()

    override fun toggleVoiceMicrophone() = controller.toggleMicrophone()

    override fun endVoiceSession() = controller.disconnect()

    private val packageInfo by lazy { runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() }

    /** Also what the about screen reports; absent when the package cannot be read. */
    val clientVersion by lazy { packageInfo?.versionName }

    /** The build number a bug report needs, next to the version name the user recognises. */
    val versionCode by lazy { packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } }

    /** A subscription is already paid for, so it is preferred when a key is also present. */
    private fun access(): OpenAiAccess? =
        when {
            chatGpt.signedIn -> SubscriptionAccess(chatGpt, clientVersion.orEmpty())
            settings.apiKey() != null -> ApiKeyAccess(settings.requireApiKey())
            else -> null
        }

    /** Best effort: the picker still accepts a typed model name when this fails. */
    fun refreshModels() {
        val access =
            access() ?: run {
                mutableModels.value = emptyMap()
                return
            }
        scope.launch {
            mutableModels.value = runCatching { catalog.load(access) }.getOrDefault(emptyMap())
        }
    }

    /** Signing in needs no browser on the phone: a code is approved on whatever device has one. */
    fun startChatGptSignIn() {
        if (signInJob?.isActive == true) return
        signInJob = scope.launch { if (signIn.run()) refreshModels() }
    }

    fun cancelChatGptSignIn() {
        signInJob?.cancel()
        signIn.reset()
    }

    fun signOutChatGpt() {
        cancelChatGptSignIn()
        chatGpt.clear()
        refreshModels()
    }

    val registry by lazy {
        CapabilityRegistry(
            buildMap {
                putAll(
                    mapOf(
                        CapabilityRegistry.MAP_SEARCH to MapIntentBackend(intentHost),
                        CapabilityRegistry.NAVIGATE to NavigationIntentBackend(intentHost),
                        CapabilityRegistry.SMS_COMPOSE to
                            chosenNumbers.remembering(MessageIntentBackend(intentHost, messageTargets), "recipient"),
                        CapabilityRegistry.SMS_SEND to
                            chosenNumbers.remembering(SmsSendBackend(this@EvaApplication, intentHost, messageTargets), "recipient"),
                        CapabilityRegistry.CONTACTS_SEARCH to
                            ContactsQueryBackend(this@EvaApplication, intentHost, ::contactHistory),
                        CapabilityRegistry.CONVERSATIONS_SEARCH to
                            MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.CONVERSATIONS),
                        CapabilityRegistry.CONVERSATION_READ to
                            MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.MESSAGES),
                        CapabilityRegistry.SET_ALARM to
                            intent("Alarm set.", "No clock app accepted this alarm.", NativeIntents::alarm),
                        CapabilityRegistry.SET_TIMER to
                            intent("Timer started.", "No clock app accepted this timer.", NativeIntents::timer),
                        CapabilityRegistry.DIAL to
                            chosenNumbers.remembering(
                                intent("Dialer opened.", "No phone app is available.", NativeIntents::dial),
                                "number",
                            ),
                        CapabilityRegistry.WEB_SEARCH to
                            intent("Web search opened.", "No browser or search app is available.", NativeIntents::webSearch),
                        CapabilityRegistry.OPEN_URL to
                            intent("Web page opened.", "No browser is available.", NativeIntents::openUrl),
                        CapabilityRegistry.EMAIL_COMPOSE to
                            intent("Email draft opened. Send it from your mail app.", "No email app is available.", NativeIntents::email),
                        CapabilityRegistry.CALENDAR_EVENT to
                            intent(
                                "Calendar event opened. Save it in your calendar.",
                                "No calendar app is available.",
                                NativeIntents::calendarEvent,
                            ),
                        CapabilityRegistry.OPEN_APP to
                            intent("App opened.", "No installed app matches that name.") {
                                NativeIntents.launchApp(this@EvaApplication, it)
                            },
                        CapabilityRegistry.OPEN_SETTINGS to
                            intent("Settings opened.", "That settings screen is unavailable on this device.", NativeIntents::settings),
                    ),
                )
                shizukuShellHost?.let { host ->
                    put(
                        CapabilityRegistry.DEVICE_STATE_GET,
                        AppFunctionsBackend(host, AppFunctionsBackend.Operation.GET),
                    )
                    put(
                        CapabilityRegistry.DEVICE_STATE_SET,
                        AppFunctionsBackend(host, AppFunctionsBackend.Operation.SET),
                    )
                    put(
                        CapabilityRegistry.DEVICE_STATE_METADATA,
                        AppFunctionsBackend(host, AppFunctionsBackend.Operation.METADATA),
                    )
                }
            },
        )
    }
    private val contactKeywords by lazy { ContactNameKeywords(this, ::contactHistory) }
    val controller by lazy {
        val repository = SqliteInvocationRepository(this)
        ProviderSessionController(
            registry = registry,
            dispatcher = CapabilityDispatcher(registry, repository),
            // A blank link means the phone talks to OpenAI itself; a link means the paired host bridge.
            providerFactory = { link ->
                if (link.isBlank()) {
                    val access = access() ?: error("Sign in with ChatGPT, add an API key, or paste a paired host link.")
                    OpenAiResponsesProvider(access, settings.textModel, reasoningEffort = settings.reasoningEffort)
                } else {
                    BrokerConversationProvider(BrokerEndpoint.parse(link))
                }
            },
            mediaFactory = { mediaFactory.create(RealtimeMediaConfig()) },
            voiceProviderFactory = { link, audio ->
                if (link.isBlank()) {
                    val access = access() ?: error("Sign in with ChatGPT, add an API key, or paste a paired host link.")
                    OpenAiRealtimeProvider(access, audio, settings.realtimeModel)
                } else {
                    val endpoint = BrokerEndpoint.parse(link)
                    BrokerConversationProvider(endpoint, offerSdp = audio.createOffer(), onAnswer = audio::acceptAnswer)
                }
            },
            repository = repository,
            scope = scope,
            voiceLookupRetries = { settings.voiceLookupRetries },
            voiceKeywords = { contactKeywords.names() },
        ).also { controller ->
            scope.launch {
                controller.state.collect { mutableVoiceSession.value = VoiceSessionStatus(it.mediaState, it.mediaControls) }
            }
            scope.launch {
                controller.state
                    .map { it.voiceMode && it.providerStatus != ProviderStatus.DISCONNECTED }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) VoiceSessionService.start(this@EvaApplication) else VoiceSessionService.stop(this@EvaApplication)
                    }
            }
        }
    }
}

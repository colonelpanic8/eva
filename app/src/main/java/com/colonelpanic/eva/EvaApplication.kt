package com.colonelpanic.eva

import android.app.Application
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import com.colonelpanic.eva.adapters.android.AndroidExtensionConnector
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.adapters.android.AndroidMediaLauncher
import com.colonelpanic.eva.adapters.android.AndroidMediaLibraryQueueClient
import com.colonelpanic.eva.adapters.android.AndroidMediaSessions
import com.colonelpanic.eva.adapters.android.AppFunctionsBackend
import com.colonelpanic.eva.adapters.android.ContactHistory
import com.colonelpanic.eva.adapters.android.ContactNameKeywords
import com.colonelpanic.eva.adapters.android.ContactsQueryBackend
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.adapters.android.IntentBackend
import com.colonelpanic.eva.adapters.android.MapIntentBackend
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.adapters.android.MediaControlBackend
import com.colonelpanic.eva.adapters.android.MediaLibraryQueueProvider
import com.colonelpanic.eva.adapters.android.MediaPlayBackend
import com.colonelpanic.eva.adapters.android.MediaQueueBackend
import com.colonelpanic.eva.adapters.android.MessageIntentBackend
import com.colonelpanic.eva.adapters.android.MessageTargets
import com.colonelpanic.eva.adapters.android.MessagingReadBackend
import com.colonelpanic.eva.adapters.android.MessagingStore
import com.colonelpanic.eva.adapters.android.NativeIntents
import com.colonelpanic.eva.adapters.android.NavigationIntentBackend
import com.colonelpanic.eva.adapters.android.ObservationStore
import com.colonelpanic.eva.adapters.android.ShizukuShellHost
import com.colonelpanic.eva.adapters.android.SmsSendBackend
import com.colonelpanic.eva.adapters.android.SpotifyQueueProvider
import com.colonelpanic.eva.adapters.android.UiControlBackend
import com.colonelpanic.eva.adapters.android.observeExtensionPackages
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.VoiceSessionHost
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.audio.VoiceSessionStatus
import com.colonelpanic.eva.audio.webrtc.WebRtcMediaSessionFactory
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.extensions.ExtensionConnectionManager
import com.colonelpanic.eva.capability.extensions.ExtensionDiscovery
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionRuntime
import com.colonelpanic.eva.capability.extensions.InstalledServiceAdapter
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.TurnWorkHost
import com.colonelpanic.eva.conversation.TurnWorkService
import com.colonelpanic.eva.conversation.WorkNotifications
import com.colonelpanic.eva.data.AppearanceSettings
import com.colonelpanic.eva.data.CapabilitySettings
import com.colonelpanic.eva.data.ChatGptAccountStore
import com.colonelpanic.eva.data.ChosenNumbers
import com.colonelpanic.eva.data.ExtensionGrantFile
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.MessagingSettings
import com.colonelpanic.eva.data.OpenAiSettings
import com.colonelpanic.eva.data.PromptStore
import com.colonelpanic.eva.data.SpotifyAccountStore
import com.colonelpanic.eva.data.SqliteConversationStore
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.data.configuration.EvaConfigurationManager
import com.colonelpanic.eva.messaging.MessagingBackend
import com.colonelpanic.eva.messaging.NotificationMessages
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
import com.colonelpanic.eva.providers.spotify.SpotifyApi
import com.colonelpanic.eva.providers.spotify.SpotifyConnect
import com.colonelpanic.eva.providers.spotify.SpotifyLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EvaApplication :
    Application(),
    VoiceSessionHost,
    TurnWorkHost {
    val intentHost = AndroidIntentHost()
    val shizukuShellHost by lazy { if (Build.VERSION.SDK_INT >= 37) ShizukuShellHost(this) else null }

    /** Screen control needs Shizuku too, but not Android 17: its helper only needs UiAutomation. */
    val deviceControlHost by lazy { if (Build.VERSION.SDK_INT >= 30) DeviceControlHost(this) else null }
    private val observations by lazy { ObservationStore(elapsedMillis = SystemClock::elapsedRealtime) }

    private fun intent(
        success: String,
        missing: String,
        build: (Map<String, String>) -> android.content.Intent?,
    ) = IntentBackend(intentHost, success, missing, build)

    private val messagingStore by lazy { MessagingStore(this) }
    private val mediaSessions by lazy { AndroidMediaSessions(this) }
    private val mediaLauncher by lazy { AndroidMediaLauncher(this) }
    private val mediaLibraryQueue by lazy { AndroidMediaLibraryQueueClient(this) }
    private val messageTargets by lazy { MessageTargets(intentHost, messagingStore) }
    val configuration by lazy { EvaConfigurationManager(this) }
    val chosenNumbers by lazy { ChosenNumbers(this, onChanged = configuration::onLocalChange) }

    private suspend fun contactHistory() = ContactHistory(messagingStore.lastMessaged(), chosenNumbers.all())

    private val mediaFactory by lazy { WebRtcMediaSessionFactory(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings by lazy { OpenAiSettings(this, configuration::onLocalChange, configuration::onCredentialChange) }
    val appearance by lazy { AppearanceSettings(this, configuration::onLocalChange) }
    val capabilities by lazy { CapabilitySettings(this, configuration::onLocalChange) }
    val prompts by lazy { PromptStore(this, onChanged = configuration::onLocalChange) }
    val chatGpt by lazy {
        ChatGptAccountStore(this, onChanged = configuration::onLocalChange, onCredentialChanged = configuration::onCredentialChange)
    }
    val signIn by lazy { ChatGptSignIn(save = chatGpt::save) }
    private val spotifyLogin by lazy { SpotifyLogin() }
    val spotify by lazy {
        SpotifyAccountStore(
            this,
            spotifyLogin,
            onChanged = configuration::onLocalChange,
            onCredentialChanged = configuration::onCredentialChange,
        )
    }
    val spotifyConnect by lazy { SpotifyConnect(spotifyLogin, spotify::save) }
    private val spotifyApi by lazy { SpotifyApi(spotify::accessToken) }
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

    override fun interruptWork(reason: String) = controller.interruptAll(reason)

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

    /** Prompt edits outlive the screen that made them, so they run here rather than in an activity scope. */
    fun editPrompt(action: suspend PromptStore.() -> Unit) {
        scope.launch { prompts.action() }
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

    fun completeSpotifyRedirect(uri: Uri) {
        scope.launch { spotifyConnect.complete(uri) }
    }

    val messagingSettings by lazy {
        MessagingSettings(this, configuration::onLocalChange, configuration::onMessagingReplyChange)
    }
    val notificationMessages by lazy {
        NotificationMessages(
            enabled = {
                messagingSettings.state.value.enabled &&
                    MediaControlAccess
                        .isGranted(this) &&
                    !getSystemService(KeyguardManager::class.java).isDeviceLocked
            },
            canReply = { it in messagingSettings.state.value.replies },
            clock = SystemClock::elapsedRealtime,
        )
    }

    val registry by lazy {
        val queueProviders =
            listOf(
                SpotifyQueueProvider(spotifyApi) {
                    spotify.account.value != null && spotify.clientId.value != null
                },
            ) + mediaLibraryQueue.apps().map { MediaLibraryQueueProvider(it, mediaLibraryQueue) }
        CapabilityRegistry(
            buildMap {
                putAll(
                    mapOf(
                        CapabilityRegistry.MAP_SEARCH to MapIntentBackend(intentHost),
                        CapabilityRegistry.NAVIGATE to NavigationIntentBackend(intentHost),
                        CapabilityRegistry.SMS_COMPOSE to
                            chosenNumbers.remembering(MessageIntentBackend(intentHost, messageTargets), "recipient"),
                        CapabilityRegistry.SMS_SEND to
                            MessagingBackend(
                                MessagingBackend.Operation.SEND,
                                chosenNumbers.remembering(SmsSendBackend(this@EvaApplication, intentHost, messageTargets), "recipient"),
                                notificationMessages,
                            ),
                        CapabilityRegistry.CONTACTS_SEARCH to
                            ContactsQueryBackend(this@EvaApplication, intentHost, ::contactHistory),
                        CapabilityRegistry.CONVERSATIONS_SEARCH to
                            MessagingBackend(
                                MessagingBackend.Operation.SEARCH,
                                MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.CONVERSATIONS),
                                notificationMessages,
                            ),
                        CapabilityRegistry.CONVERSATION_READ to
                            MessagingBackend(
                                MessagingBackend.Operation.READ,
                                MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.MESSAGES),
                                notificationMessages,
                            ),
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
                        CapabilityRegistry.MEDIA_CONTROL to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.CONTROL),
                        CapabilityRegistry.MEDIA_NOW_PLAYING to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.STATUS),
                        CapabilityRegistry.MEDIA_VOLUME to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.VOLUME),
                        CapabilityRegistry.MEDIA_PLAY to
                            MediaPlayBackend(
                                mediaLauncher,
                                mediaSessions,
                                intent("Asked a music app to play that.", "No app on this phone offers to play a request by name.") {
                                    NativeIntents.playMedia(this@EvaApplication, it)
                                },
                            ),
                        CapabilityRegistry.MEDIA_QUEUE to
                            MediaQueueBackend(queueProviders),
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
                deviceControlHost?.let { host ->
                    val exposed = { capabilities.screenControlEnabled }
                    put(CapabilityRegistry.UI_OBSERVE, UiControlBackend(host, observations, UiControlBackend.Operation.OBSERVE, exposed))
                    put(CapabilityRegistry.UI_TAP, UiControlBackend(host, observations, UiControlBackend.Operation.TAP, exposed))
                    put(CapabilityRegistry.UI_SET_TEXT, UiControlBackend(host, observations, UiControlBackend.Operation.SET_TEXT, exposed))
                }
            },
        )
    }
    private val extensionScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> logExtensionFailure(failure) },
        )
    val packageSettings by lazy {
        com.colonelpanic.eva.data
            .PackageSettings(
                this,
                onChanged = configuration::onLocalChange,
                onCredentialChanged = configuration::onCredentialChange,
            )
    }
    private val boundedExecution by lazy {
        com.colonelpanic.eva.capability
            .BoundedExecution(extensionScope)
    }
    private val packageAdapter by lazy {
        com.colonelpanic.eva.adapters.declarative.PackageAdapter(
            packageSettings::load,
            { identity ->
                com.colonelpanic.eva.adapters.android.AndroidDeclarativeHost(
                    intentHost,
                    com.colonelpanic.eva.adapters.declarative.PackageHttpClient(credential = { origin, name ->
                        packageSettings.credential(identity, origin, name)
                    }),
                )
            },
            boundedExecution,
            unavailable = packageSettings::unavailable,
        ) { identity, capability, proposal ->
            packageSettings.budget(identity, capability.execution.maxWaitMillis, proposal.interactionMode)
        }
    }

    val pluginBrowser by lazy {
        com.colonelpanic.eva.adapters.declarative.PluginBrowser(
            com.colonelpanic.eva.adapters.declarative.PluginRepository(
                com.colonelpanic.eva.adapters.declarative
                    .RepositoryHttpClient()::fetch,
            ),
            extensionScope,
            packageSettings.repositorySource,
            packageSettings::imported,
            {
                packageManager
                    .queryIntentActivities(
                        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                        0,
                    ).map { it.activityInfo.packageName }
                    .toSet()
            },
            packageSettings::saveRepository,
            { preview ->
                registry.changeAuthorization {
                    packageSettings.installPlugin(preview)
                    packageAdapter.refresh()
                }
            },
            { instance ->
                registry.changeAuthorization {
                    packageSettings.removePlugin(instance)
                    packageAdapter.refresh()
                }
            },
        )
    }

    fun savePackageServer(
        id: String,
        sourceOrigin: String,
        serviceName: String,
        url: String,
        username: String,
        password: String,
    ): String? {
        val error = packageSettings.save(id, sourceOrigin, serviceName, url, username, password)
        if (error == null) packageAdapter.refresh()
        return error
    }

    fun clearPackageServer(
        id: String,
        sourceOrigin: String,
    ) {
        packageSettings.clear(id, sourceOrigin)
        packageAdapter.refresh()
    }

    val extensions by lazy {
        ExtensionRuntime(
            registry,
            com.colonelpanic.eva.capability.extensions.CompositeCapabilityAdapter(
                listOf(
                    com.colonelpanic.eva.capability.extensions.IsolatedCapabilityAdapter(
                        "Installed extension apps",
                        extensionScope,
                        ::logExtensionFailure,
                    ) {
                        val connector = AndroidExtensionConnector(this)
                        val connections = ExtensionConnectionManager(connector, SystemClock::elapsedRealtime)
                        InstalledServiceAdapter(
                            ExtensionDiscovery(connector::scan, connections, extensionScope, ::logExtensionFailure),
                            connections,
                        )
                    },
                    com.colonelpanic.eva.capability.extensions.IsolatedCapabilityAdapter(
                        "Declarative packages",
                        extensionScope,
                        ::logExtensionFailure,
                    ) { packageAdapter },
                ),
                extensionScope,
            ),
            ExtensionGrants(ExtensionGrantFile(this)),
            extensionScope,
            configuration::onLocalChange,
            configuration::onGrantChange,
        )
    }

    private fun logExtensionFailure(failure: Throwable) {
        android.util.Log.e("EvaExtensions", "Extension startup or refresh failed", failure)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            observeExtensionPackages(this, extensions::packageChanged)
            extensions.refresh()
            configuration.start()
        } catch (failure: Throwable) {
            com.colonelpanic.eva.capability.extensions
                .rethrowFatalExtensionFailure(failure)
            logExtensionFailure(failure)
        }
    }

    private val contactKeywords by lazy { ContactNameKeywords(this, ::contactHistory) }
    val controller by lazy {
        val journal = JournalDatabase(this, SqliteInvocationRepository.DATABASE_NAME)
        val repository = SqliteInvocationRepository(journal)
        ThreadController(
            registry = registry,
            dispatcher = CapabilityDispatcher(registry, repository),
            store = SqliteConversationStore(journal),
            onBackgroundAnswer = { WorkNotifications.answered(this, it) },
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
                    OpenAiRealtimeProvider(access, audio, settings.realtimeModel, settings.voiceReasoningEffort)
                } else {
                    val endpoint = BrokerEndpoint.parse(link)
                    BrokerConversationProvider(endpoint, offerSdp = audio.createOffer(), onAnswer = audio::acceptAnswer)
                }
            },
            repository = repository,
            scope = scope,
            voiceLookupRetries = { settings.voiceLookupRetries },
            voiceKeywords = { contactKeywords.names() },
            hiddenCapabilities = { if (capabilities.screenControlEnabled) emptySet() else CapabilityRegistry.SCREEN_CONTROL },
            prompt = { prompts.load() },
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
            scope.launch {
                // A turn that outlives its call needs the process kept alive; the voice service already does that.
                combine(controller.working, controller.state) { working, state ->
                    working.isNotEmpty() && !(state.voiceMode && state.providerStatus != ProviderStatus.DISCONNECTED)
                }.distinctUntilChanged()
                    .collect { active ->
                        if (active) TurnWorkService.start(this@EvaApplication) else TurnWorkService.stop(this@EvaApplication)
                    }
            }
        }
    }
}

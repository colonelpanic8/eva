package com.colonelpanic.eva

import android.app.Application
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import com.colonelpanic.eva.adapters.android.AndroidExtensionConnector
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.adapters.android.AndroidMediaApps
import com.colonelpanic.eva.adapters.android.AndroidMediaLauncher
import com.colonelpanic.eva.adapters.android.AndroidMediaLibraryQueueClient
import com.colonelpanic.eva.adapters.android.AndroidMediaSessions
import com.colonelpanic.eva.adapters.android.AppFunctionsBackend
import com.colonelpanic.eva.adapters.android.ContactHistory
import com.colonelpanic.eva.adapters.android.ContactNameKeywords
import com.colonelpanic.eva.adapters.android.ContactsQueryBackend
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.adapters.android.IntentBackend
import com.colonelpanic.eva.adapters.android.MediaAdapter
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.adapters.android.MediaControlBackend
import com.colonelpanic.eva.adapters.android.MediaLibraryQueueProvider
import com.colonelpanic.eva.adapters.android.MediaPlayBackend
import com.colonelpanic.eva.adapters.android.MessageIntentBackend
import com.colonelpanic.eva.adapters.android.MessageTargets
import com.colonelpanic.eva.adapters.android.MessagingReadBackend
import com.colonelpanic.eva.adapters.android.MessagingStore
import com.colonelpanic.eva.adapters.android.NativeIntents
import com.colonelpanic.eva.adapters.android.ShizukuShellHost
import com.colonelpanic.eva.adapters.android.SmsSendBackend
import com.colonelpanic.eva.adapters.android.SpotifyQueueProvider
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
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.data.AppearanceSettings
import com.colonelpanic.eva.data.CapabilitySettings
import com.colonelpanic.eva.data.ChatGptAccountStore
import com.colonelpanic.eva.data.ChosenNumbers
import com.colonelpanic.eva.data.DiagnosticsSettings
import com.colonelpanic.eva.data.ExtensionGrantFile
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.MessagingSettings
import com.colonelpanic.eva.data.OpenAiSettings
import com.colonelpanic.eva.data.PromptStore
import com.colonelpanic.eva.data.SpotifyAccountStore
import com.colonelpanic.eva.data.SqliteConversationStore
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.data.configuration.EvaConfigurationManager
import com.colonelpanic.eva.devicecontrol.ScreenActions
import com.colonelpanic.eva.diagnostics.EvaTrace
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EvaApplication :
    Application(),
    VoiceSessionHost,
    TurnWorkHost {
    val intentHost = AndroidIntentHost(this, deviceTaskRunning = { deviceTasks.running.value != null })
    val shizukuShellHost by lazy { if (Build.VERSION.SDK_INT >= 37) ShizukuShellHost(this) else null }

    /** Screen control needs Shizuku too, but not Android 17: its helper only needs UiAutomation. */
    val deviceControlHost by lazy { if (Build.VERSION.SDK_INT >= 30) DeviceControlHost(this) else null }
    private val screenActions by lazy {
        ScreenActions(
            enabled = { capabilities.screenControlEnabled },
            select = ::screenActionBackend,
            elapsedMillis = SystemClock::elapsedRealtime,
        )
    }

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

    private suspend fun contactHistory() =
        ContactHistory(messagingStore.lastMessaged(), chosenNumbers.all(), messagingStore.phoneNumberKey())

    private val mediaFactory by lazy { WebRtcMediaSessionFactory(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings by lazy { OpenAiSettings(this, configuration::onLocalChange, configuration::onCredentialChange) }
    val appearance by lazy { AppearanceSettings(this, configuration::onLocalChange) }
    val diagnostics by lazy { DiagnosticsSettings(this, configuration::onLocalChange) }
    val capabilities by lazy { CapabilitySettings(this, configuration::onLocalChange, configuration::onCredentialChange) }
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

    override fun voiceUnavailable(reason: String) = controller.voiceUnavailable(reason)

    override fun endVoiceSession() = controller.disconnect()

    override fun workTasks() = controller.taskSnapshots.value

    override fun workCoverageChanged(coverage: com.colonelpanic.eva.conversation.WorkCoverage) = controller.setWorkCoverage(coverage)

    override fun workCoverageLimited(reason: String) {
        if (controller.workCoverageNotice(reason)) WorkNotifications.limited(this, reason)
    }

    override fun stopAllWork() = controller.stopBackgroundTasks()

    override fun needsWorkCoverage(): Boolean =
        controller.needsWorkCoverage.value ||
            (controller.state.value.voiceMode && controller.state.value.providerStatus != ProviderStatus.DISCONNECTED)

    override fun interruptWork(reason: String) = controller.interruptBackgroundWork(reason)

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
        MessagingSettings(this, configuration::onLocalChange, configuration::onMessagingReplyChange, configuration::onCredentialChange)
    }

    /** Linked messaging accounts behind self-hosted bridges; tokens resolve only for a bridge's own origin. */
    val bridgeMessaging by lazy {
        com.colonelpanic.eva.messaging.BridgeMessaging(
            bridges = { messagingSettings.state.value.bridges },
            http =
                com.colonelpanic.eva.adapters.declarative.PackageHttpClient(credential = { origin, name ->
                    messagingSettings.bridgeCredential(name)?.takeIf { it.origin == origin }
                }),
        )
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

    val memories by lazy {
        com.colonelpanic.eva.data
            .MemoryStore(
                com.colonelpanic.eva.data
                    .AndroidMemoryFiles(filesDir),
            )
    }

    val deviceTasks by lazy {
        com.colonelpanic.eva.devicecontrol
            .DeviceTaskCoordinator(unavailable = ::deviceTaskUnavailableReason, releaseScope = scope, wording = {
                prompts.wording.value
            }) { createDeviceTaskAgent() }
    }

    private suspend fun deviceTaskUnavailableReason(): String? {
        if (!capabilities.screenControlEnabled) return "Screen control is switched off in EVA's settings."
        val problems = mutableListOf<String>()
        for (backend in capabilities.deviceTask.backends) {
            val problem = backendProblem(backend) ?: return null
            problems += "${backendLabel(backend)}: $problem"
        }
        return "No device-task backend is ready. ${problems.joinToString(" ")}"
    }

    private suspend fun backendProblem(backend: String): String? =
        when (backend) {
            "portal" -> portalProblem()
            else -> deviceControlHost?.unavailableReason() ?: if (deviceControlHost == null) SCREEN_CONTROL_API else null
        }

    private fun backendLabel(backend: String) = if (backend == "portal") "Portal" else "Shizuku"

    private val portalHttp by lazy { okhttp3.OkHttpClient() }

    private suspend fun portalProblem(): String? {
        val token = capabilities.portalToken() ?: return PORTAL_TOKEN_MISSING
        val health =
            com.colonelpanic.eva.devicecontrol.portal
                .PortalClient(capabilities.deviceTask.portalPort, { token }, portalHttp)
                .health()
        return when (health) {
            com.colonelpanic.eva.devicecontrol.portal.PortalHealth.READY -> {
                null
            }

            com.colonelpanic.eva.devicecontrol.portal.PortalHealth.UNAUTHORIZED -> {
                "Portal rejected EVA's token. Save Portal's current token in EVA's Screen control settings."
            }

            com.colonelpanic.eva.devicecontrol.portal.PortalHealth.UNREACHABLE -> {
                "Portal is not running. Turn on Portal's accessibility service."
            }
        }
    }

    /** Capabilities a settings switch removes from every session's catalog. */
    fun switchedOffCapabilities(): Set<String> =
        buildSet {
            if (!capabilities.screenControlEnabled) addAll(CapabilityRegistry.SCREEN_CONTROL)
            if (!capabilities.webResearch.enabled) add(com.colonelpanic.eva.web.WebResearchBackend.ID)
        }

    val screenControl by lazy {
        com.colonelpanic.eva.devicecontrol.ScreenControlMonitor(
            enabled = { capabilities.screenControlEnabled },
            backends = { capabilities.deviceTask.backends },
            setup = { backend ->
                if (backend == "portal") {
                    if (capabilities.portalToken() == null) PORTAL_TOKEN_MISSING else null
                } else {
                    val host = deviceControlHost
                    if (host == null) SCREEN_CONTROL_API else host.accessStatus().takeUnless { it == DeviceControlHost.ALLOWED }
                }
            },
            probe = { backend -> if (backend == "portal") portalProblem() else null },
            label = ::backendLabel,
        )
    }

    private fun deviceBackend(
        name: String,
        onDeviceTiming: (com.colonelpanic.eva.devicecontrol.portal.ActionTiming) -> Unit = {},
    ): com.colonelpanic.eva.devicecontrol.DeviceBackend {
        val options = capabilities.deviceTask
        val transport =
            if (name == "portal") {
                com.colonelpanic.eva.devicecontrol.portal.PortalClient(
                    port = options.portalPort,
                    token = { capabilities.portalToken() ?: error("Provision the Portal token in settings.") },
                )
            } else {
                com.colonelpanic.eva.devicecontrol.ShizukuPortalTransport(
                    checkNotNull(deviceControlHost) { SCREEN_CONTROL_API },
                )
            }
        return com.colonelpanic.eva.devicecontrol.ReportingDeviceBackend(
            backendLabel(name),
            com.colonelpanic.eva.devicecontrol.portal.PortalBackend(
                transport,
                launchAliases = options.launchAliases,
                timing = onDeviceTiming,
                backend = name,
            ),
        ) { failure -> screenControl.record(name, failure) }
    }

    /** Direct screen tools use the device-task order, handing over before any input like tasks do. */
    private fun screenActionBackend(): ScreenActions.Choice {
        val options = capabilities.deviceTask
        if (options.backends.isEmpty()) {
            return ScreenActions.Choice.Unavailable(
                "Every screen control backend is turned off in EVA's settings.",
            )
        }
        return ScreenActions.Choice.Ready(
            "${options.backends}:${options.portalPort}:${options.launchAliases}",
            options.backends.map { name ->
                ScreenActions.Route(backendLabel(name), { backendProblem(name) }) { deviceBackend(name) }
            },
        )
    }

    internal fun createDeviceTaskAgent(
        onDeviceTiming: (com.colonelpanic.eva.devicecontrol.portal.ActionTiming) -> Unit = {},
        decorateModel: (
            com.colonelpanic.eva.devicecontrol.worker.WorkerModel,
        ) -> com.colonelpanic.eva.devicecontrol.worker.WorkerModel = { it },
    ): com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent {
        check(capabilities.screenControlEnabled) { "Screen control is disabled." }
        val options = capabilities.deviceTask
        val backend =
            com.colonelpanic.eva.devicecontrol.PreferredDeviceBackend(
                options.backends.map { name ->
                    com.colonelpanic.eva.devicecontrol.PreferredDeviceBackend.Candidate(
                        backendLabel(name),
                        problem = { backendProblem(name) },
                    ) { deviceBackend(name, onDeviceTiming) }
                },
                onFallback = { name, problem -> EvaTrace.info("device_task.backend_fallback", "backend" to name, "problem" to problem) },
            )
        screenActions.reset()
        return com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent(
            backend,
            decorateModel(
                com.colonelpanic.eva.devicecontrol.OpenAiWorkerModel(
                    checkNotNull(access()) {
                        "Sign in to OpenAI first."
                    },
                    options.model,
                    options.reasoningEffort,
                ),
            ),
            com.colonelpanic.eva.devicecontrol
                .workerWording(prompts.wording.value),
            com.colonelpanic.eva.devicecontrol.worker
                .WorkerSettings(
                    options.maxSteps,
                    options.maxMillis,
                    options.modelTimeoutMillis,
                    options.maxScreens,
                    options.historyLines,
                    options.maxRefusals,
                    options.maxScreenshots,
                    options.launchAliases,
                ),
        )
    }

    val registry by lazy {
        CapabilityRegistry(
            buildMap {
                put(CapabilityRegistry.DEVICE_TASK, deviceTasks)
                put(
                    com.colonelpanic.eva.web.WebResearchBackend.ID,
                    com.colonelpanic.eva.web.WebResearchBackend(
                        access = ::access,
                        configuration = { capabilities.webResearch },
                        wording = { prompts.wording.value },
                    ),
                )
                putAll(
                    com.colonelpanic.eva.capability.MemoryCapabilities
                        .backends(memories),
                )
                putAll(
                    mapOf(
                        CapabilityRegistry.SMS_COMPOSE to
                            chosenNumbers.remembering(MessageIntentBackend(intentHost, messageTargets), "recipient"),
                        CapabilityRegistry.SMS_SEND to
                            MessagingBackend(
                                MessagingBackend.Operation.SEND,
                                chosenNumbers.remembering(SmsSendBackend(this@EvaApplication, intentHost, messageTargets), "recipient"),
                                notificationMessages,
                                bridgeMessaging,
                            ),
                        CapabilityRegistry.CONTACTS_SEARCH to
                            ContactsQueryBackend(this@EvaApplication, intentHost, ::contactHistory),
                        CapabilityRegistry.CONVERSATIONS_SEARCH to
                            MessagingBackend(
                                MessagingBackend.Operation.SEARCH,
                                MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.CONVERSATIONS),
                                notificationMessages,
                                bridgeMessaging,
                            ),
                        CapabilityRegistry.CONVERSATION_READ to
                            MessagingBackend(
                                MessagingBackend.Operation.READ,
                                MessagingReadBackend(intentHost, messagingStore, MessagingReadBackend.Operation.MESSAGES),
                                notificationMessages,
                                bridgeMessaging,
                            ),
                        CapabilityRegistry.DIAL to
                            chosenNumbers.remembering(
                                com.colonelpanic.eva.adapters.android.PhoneCallBackend(
                                    this@EvaApplication,
                                    intent("Dialer opened with the number.", "No phone app is available.", NativeIntents::dial),
                                ),
                                "number",
                            ),
                        CapabilityRegistry.LOCATION_CURRENT to
                            com.colonelpanic.eva.adapters.android
                                .CurrentLocationBackend(this@EvaApplication),
                        CapabilityRegistry.OPEN_APP to
                            intent("App opened.", "No installed app matches that name.") {
                                NativeIntents.launchApp(this@EvaApplication, it)
                            },
                        CapabilityRegistry.MEDIA_CONTROL to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.CONTROL),
                        CapabilityRegistry.MEDIA_NOW_PLAYING to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.STATUS),
                        CapabilityRegistry.MEDIA_VOLUME to
                            MediaControlBackend(mediaSessions, MediaControlBackend.Operation.VOLUME),
                        CapabilityRegistry.MEDIA_PLAY to
                            MediaPlayBackend(
                                mediaSessions,
                                intent("Asked a music app to play that.", "No app on this phone offers to play a request by name.") {
                                    NativeIntents.playMedia(it)
                                },
                            ),
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
                listOf(
                    CapabilityRegistry.UI_OBSERVE to ScreenActions.Operation.OBSERVE,
                    CapabilityRegistry.UI_TAP to ScreenActions.Operation.TAP,
                    CapabilityRegistry.UI_SET_TEXT to ScreenActions.Operation.SET_TEXT,
                    CapabilityRegistry.UI_SCROLL to ScreenActions.Operation.SCROLL,
                    CapabilityRegistry.UI_PRESS_ENTER to ScreenActions.Operation.PRESS_ENTER,
                    CapabilityRegistry.UI_NAVIGATE to ScreenActions.Operation.NAVIGATE,
                ).forEach { (id, operation) -> put(id, screenActions.backend(operation)) }
            },
            com.colonelpanic.eva.capability.BundledCapabilities.definitions,
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
                    this,
                )
            },
            boundedExecution,
        ) { identity, capability, proposal ->
            packageSettings.budget(identity, capability.execution.maxWaitMillis, proposal.interactionMode)
        }
    }

    val pluginBrowser by lazy {
        com.colonelpanic.eva.adapters.declarative.PluginBrowser(
            com.colonelpanic.eva.adapters.declarative.PluginRepository(
                java.io.File(noBackupFilesDir, "extension-catalogs"),
                com.colonelpanic.eva.adapters.declarative
                    .RepositoryHttpClient()::fetch,
            ),
            extensionScope,
            packageSettings::repositorySources,
            packageSettings::imported,
            {
                packageManager
                    .queryIntentActivities(
                        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                        0,
                    ).map { it.activityInfo.packageName }
                    .toSet()
            },
            packageSettings::saveRepositories,
            catalogSync,
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

    /** The policy a catalog refresh applies; see [com.colonelpanic.eva.adapters.declarative.PluginSyncPolicy]. */
    private val catalogSync by lazy {
        com.colonelpanic.eva.adapters.declarative.PluginSyncPolicy(
            packageSettings::imported,
            packageSettings::autoEnable,
            { preview ->
                var installed: com.colonelpanic.eva.adapters.declarative.InstalledPlugin? = null
                registry.changeAuthorization {
                    installed = packageSettings.installPlugin(preview)
                    packageAdapter.refresh()
                }
                checkNotNull(installed)
            },
            { identity -> extensions.adopt(identity) },
            { identity, digest, actions, enabled -> extensions.carryForward(identity, digest, actions, enabled) },
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
                    com.colonelpanic.eva.capability.extensions.IsolatedCapabilityAdapter(
                        "Media apps",
                        extensionScope,
                        ::logExtensionFailure,
                    ) {
                        MediaAdapter(
                            AndroidMediaApps(this, mediaLauncher, mediaLibraryQueue),
                            mediaSessions,
                            mediaLauncher,
                            queueFor = { app ->
                                when {
                                    app.identity.packageName == SpotifyQueueProvider.PACKAGE -> {
                                        SpotifyQueueProvider(spotifyApi) {
                                            spotify.account.value != null && spotify.clientId.value != null
                                        }
                                    }

                                    app.library != null -> {
                                        MediaLibraryQueueProvider(app.library, mediaLibraryQueue)
                                    }

                                    else -> {
                                        null
                                    }
                                }
                            },
                            intentFor = { app ->
                                if (!app.handlesSearchIntent) {
                                    null
                                } else {
                                    intent("Asked ${app.label} to play that.", "${app.label} is not accepting play requests.") {
                                        NativeIntents.playMediaIn(app.identity.packageName, it.getValue("query"))
                                    }
                                }
                            },
                            remoteFor = { app ->
                                if (app.identity.packageName != SpotifyQueueProvider.PACKAGE) {
                                    null
                                } else {
                                    com.colonelpanic.eva.adapters.android.SpotifyPlayer(
                                        spotifyApi,
                                        { spotify.account.value != null && spotify.clientId.value != null },
                                        {
                                            android.provider.Settings.Global
                                                .getString(contentResolver, "device_name")
                                                ?: android.os.Build.MODEL
                                        },
                                        { NativeIntents.wakePlayer(this, SpotifyQueueProvider.PACKAGE) },
                                    )
                                }
                            },
                        )
                    },
                ),
                extensionScope,
            ),
            ExtensionGrants(ExtensionGrantFile(this)),
            extensionScope,
            configuration::onLocalChange,
            configuration::onGrantChange,
            object : com.colonelpanic.eva.capability.extensions.DefaultGrantPolicy {
                override fun trusts(identity: com.colonelpanic.eva.capability.extensions.AdapterIdentity) =
                    com.colonelpanic.eva.capability.extensions.DefaultProviders
                        .trusts(identity)

                override fun autoEnable(instance: String) = packageSettings.autoEnable(instance)

                override fun setAutoEnable(
                    instance: String,
                    enabled: Boolean,
                ) = packageSettings.setAutoEnable(instance, enabled)
            },
        )
    }

    private fun logExtensionFailure(failure: Throwable) {
        android.util.Log.e("EvaExtensions", "Extension startup or refresh failed", failure)
        EvaTrace.info("extensions.failed", "error" to failure.javaClass.simpleName)
    }

    override fun onCreate() {
        super.onCreate()
        diagnosticsExport.install(scope, diagnostics.verboseLoggingFlow)
        if (settings.takeLegacyOneShotExternal() == false) {
            editPrompt { update { it.selectCallMode(VoiceCallMode.OPEN_CONVERSATION) } }
        }
        // An assistant launch may never open the activity, so the source is followed from here too.
        editPrompt { follow() }
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
    private val journal by lazy { JournalDatabase(this, SqliteInvocationRepository.DATABASE_NAME) }
    val invocations by lazy { SqliteInvocationRepository(journal) }
    val conversations by lazy { SqliteConversationStore(journal) }
    val diagnosticsExport by lazy {
        com.colonelpanic.eva.diagnostics
            .AndroidDiagnostics(this)
    }

    val controller by lazy {
        val repository = invocations
        ThreadController(
            registry = registry,
            dispatcher =
                CapabilityDispatcher(registry, repository, onBackendFailure = { capability, error ->
                    android.util.Log.w("EvaDispatch", "$capability threw before reporting an outcome", error)
                    EvaTrace.info("tool.backend_threw", "capability" to capability, "error" to error.javaClass.simpleName)
                }, executeAdmitted = { proposal, backend ->
                    deviceTasks.executeAdmitted(proposal, backend, backend.usesDeviceUi(proposal))
                }),
            deviceTasks = deviceTasks,
            onWorkAccepted = { TurnWorkService.ensureStarted(this) },
            stallPeriodMillis = { capabilities.stallPeriodSeconds * 1_000L },
            store = conversations,
            onBackgroundAnswer = { WorkNotifications.answered(this, it) },
            onBackgroundAnswerDelivered = { WorkNotifications.delivered(this, it) },
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
            awaitCapabilities = {
                var configured = false
                val ready =
                    kotlinx.coroutines.withTimeoutOrNull(15_000) {
                        configuration.awaitReady()
                        configured = true
                        extensions.awaitReady()
                        true
                    } == true
                if (!ready) EvaTrace.info("readiness.timeout", "configuration" to configured, "extensions" to false)
                check(ready) { "EVA is still loading configuration and extensions. Try connecting again shortly." }
            },
            voiceLookupRetries = { settings.voiceLookupRetries },
            quietHangUpMillis = { settings.quietHangUpSeconds * 1_000L },
            wording = { prompts.wording.value },
            voiceKeywords = { contactKeywords.names() },
            messagingBridges = {
                messagingSettings.state.value.bridges
                    .mapValues { it.value.label }
            },
            callEndings = { settings.callEndings.value },
            hiddenCapabilities = ::switchedOffCapabilities,
            prompt = { prompts.load() },
        ).also { controller ->
            scope.launch {
                controller.state.collect { mutableVoiceSession.value = VoiceSessionStatus(it.mediaState, it.mediaControls) }
            }
            scope.launch {
                val transition =
                    com.colonelpanic.eva.conversation.WorkServiceTransition(
                        startWork = { TurnWorkService.ensureStarted(this@EvaApplication) },
                        stopWork = { TurnWorkService.stop(this@EvaApplication) },
                        startVoice = { VoiceSessionService.start(this@EvaApplication) },
                        stopVoice = { VoiceSessionService.stop(this@EvaApplication) },
                    )
                kotlinx.coroutines.flow
                    .combine(
                        controller.state.map { it.voiceMode && it.providerStatus != ProviderStatus.DISCONNECTED }.distinctUntilChanged(),
                        controller.needsWorkCoverage,
                    ) { voice, work -> voice to work }
                    .collect { (voice, work) -> transition.update(voice, work) }
            }
            scope.launch {
                controller.needsWorkCoverage.collectLatest { needed ->
                    if (needed) {
                        while (true) {
                            controller.refreshTaskSnapshots()
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                }
            }
        }
    }
}

private const val SCREEN_CONTROL_API = "Screen control requires Android 11 or newer."
private const val PORTAL_TOKEN_MISSING = "Provision the Portal token in EVA's Screen control settings."

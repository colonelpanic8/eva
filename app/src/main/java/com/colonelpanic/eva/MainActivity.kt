package com.colonelpanic.eva

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.colonelpanic.eva.adapters.android.ContentProviderAccess
import com.colonelpanic.eva.adapters.android.EvaNotificationListener
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.assist.EvaVoiceInteractionService
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.capability.CallEnding
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.CatalogAdmission
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.WorkNotifications
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.data.PromptState
import com.colonelpanic.eva.devicecontrol.RepairOutcome
import com.colonelpanic.eva.diagnostics.AndroidDiagnostics
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.HandsFreeSurface
import com.colonelpanic.eva.ui.VoiceAccessModel
import com.colonelpanic.eva.ui.VoiceStart
import com.colonelpanic.eva.ui.about.AboutInfo
import com.colonelpanic.eva.ui.prompt.PromptActions
import com.colonelpanic.eva.ui.prompt.PromptUiState
import com.colonelpanic.eva.ui.settings.NativeCallEnding
import com.colonelpanic.eva.ui.settings.PermissionStatus
import com.colonelpanic.eva.ui.settings.SettingsActions
import com.colonelpanic.eva.ui.settings.SettingsUiState
import com.colonelpanic.eva.ui.theme.EvaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val voice: VoiceAccessModel by viewModels()
    private val shizukuAccess = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val screenControlRepairing = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    private var surface: Launch
        get() = voice.surface
        set(value) {
            voice.surface = value
        }
    private var runningWorkRequest by mutableStateOf(0)
    private var deviceAssistant by mutableStateOf(false)
    private var mediaControlAccess by mutableStateOf(false)
    private var messagingPermissions by mutableStateOf(emptyList<PermissionStatus>())
    private var contentPermissionRevision by mutableIntStateOf(0)
    private val contentPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            contentPermissionRevision++
            eva.configuration.onLocalChange()
            eva.extensions.refresh()
        }
    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            voice.requestInFlight = false
            val granted = MicrophonePermission.isGranted(this)
            val canAskAgain = granted || shouldShowRequestPermissionRationale(MicrophonePermission.PERMISSION)
            voice.update { onPermissionResult(granted, canAskAgain) }?.let(::perform)
        }

    private val eva get() = application as EvaApplication

    private val pluginFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                eva.pluginBrowser.previewFile {
                    checkNotNull(contentResolver.openInputStream(uri)) { "Could not open the selected extension file" }
                }
            }
        }

    private val configurationFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(eva.configuration::select)
        }

    private val capabilityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> eva.intentHost.onPermissionResult(granted) }

    // Both pickers hand out a grant EVA can keep, which is what lets the prompt live in a synced folder.
    private val openPromptFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { eva.editPrompt { useDocument(it, create = false) } }
        }
    private val createPromptFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
            uri?.let { eva.editPrompt { useDocument(it, create = true) } }
        }

    /**
     * Asks for everything still outstanding in one dialog run. A request launched while another is
     * open loses its answer, so an in-flight sweep is left to deliver the result instead.
     */
    private fun requestMissingPermissions() {
        if (voice.requestInFlight) return
        val missing = EvaPermissions.missing(this)
        if (missing.isEmpty()) return
        voice.requestInFlight = true
        runtimePermissions.launch(missing.toTypedArray())
    }

    /** The paired host link now lives in settings, not in a field on the conversation screen. */
    private fun startVoice() {
        perform(voice.update { start(eva.settings.hostLink(), MicrophonePermission.isGranted(this@MainActivity)) })
    }

    private fun retryMicrophone() {
        val next = voice.update { retry(MicrophonePermission.isGranted(this@MainActivity)) }
        if (next == null && voice.denial != null) openAppSettings() else next?.let(::perform)
    }

    private fun dismissDenial() {
        voice.update { dismiss() }
    }

    private fun perform(start: VoiceStart) {
        when (start) {
            is VoiceStart.Connect -> {
                eva.controller.connectVoice(
                    start.link,
                    newThread = surface.startsVoice,
                )
            }

            is VoiceStart.RequestMicrophone -> {
                requestMissingPermissions()
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun permissionLabel(permission: String): String =
        when (permission) {
            Manifest.permission.READ_CONTACTS -> "Read contacts"
            Manifest.permission.READ_SMS -> "Read SMS"
            Manifest.permission.SEND_SMS -> "Send SMS"
            else -> permission.substringAfterLast('.')
        }

    /** Whichever of the assistant screens this device actually has. */
    private fun openAssistantSettings() = openFirstAvailable(AssistantRole.settingsIntents())

    private fun showAssistant() {
        if (!EvaVoiceInteractionService.showAssistant(oneShot = true)) openAssistantSettings()
    }

    private fun openMediaControlSettings() = openFirstAvailable(MediaControlAccess.settingsIntents(this))

    /** System settings screens vary by device, so each list runs most specific first. */
    private fun openFirstAvailable(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
    }

    private fun currentLaunch(): Launch = launchFor(intent?.action, intent?.getBooleanExtra(RecognizerIntent.EXTRA_SECURE, false) == true)

    /**
     * Answers the launch that opened this instance. Stored conversations have to finish loading
     * before the controller accepts a connection, and an existing session is left alone.
     */
    private fun openHandsFree() {
        val kind = currentLaunch()
        surface = kind
        if (!kind.startsVoice) return
        if (kind.locked) showOverKeyguard()
        lifecycleScope.launch {
            val loaded = eva.controller.state.first { !it.isLoading }
            if (loaded.voiceMode && loaded.providerStatus != ProviderStatus.DISCONNECTED) return@launch
            startVoice()
        }
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun requestUnlock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (!isLocked()) surface = Launch.HANDS_FREE
                }
            },
        )
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun editMemory(edit: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { edit() }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: "EVA could not update its memory.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Answers the message a rejected value should show, or null once it is stored. */
    private fun save(action: () -> Unit): String? =
        runCatching(action).exceptionOrNull()?.let {
            it.message
                ?: "That value was not accepted."
        }

    @Composable
    private fun settingsUiState(dynamicColor: Boolean): SettingsUiState {
        val settings = eva.settings
        val hasApiKey by settings.hasApiKey.collectAsStateWithLifecycle()
        val hasHostLink by settings.hasHostLink.collectAsStateWithLifecycle()
        val hasPortalToken by eva.capabilities.hasPortalToken.collectAsStateWithLifecycle()
        val textModel by settings.textModelFlow.collectAsStateWithLifecycle()
        val realtimeModel by settings.realtimeModelFlow.collectAsStateWithLifecycle()
        val reasoningEffort by settings.reasoningEffortFlow.collectAsStateWithLifecycle()
        val voiceReasoningEffort by settings.voiceReasoningEffortFlow.collectAsStateWithLifecycle()
        val voiceLookupRetries by settings.voiceLookupRetriesFlow.collectAsStateWithLifecycle()
        val callEndings by settings.callEndings.collectAsStateWithLifecycle()
        val prompt by eva.prompts.state.collectAsStateWithLifecycle()
        val models by eva.availableModels.collectAsStateWithLifecycle()
        val account by eva.chatGpt.account.collectAsStateWithLifecycle()
        val signIn by eva.signIn.state.collectAsStateWithLifecycle()
        val messaging by eva.messagingSettings.state.collectAsStateWithLifecycle()
        val bridgeCredentialRevision by eva.messagingSettings.credentialRevision.collectAsStateWithLifecycle()
        val messagingApps by eva.notificationMessages.apps.collectAsStateWithLifecycle()
        val messagingBridgeChecks by eva.bridgeMessaging.checks.collectAsStateWithLifecycle()
        val rememberedNumbers by eva.chosenNumbers.count.collectAsStateWithLifecycle()
        val memories by eva.memories.state.collectAsStateWithLifecycle()
        val skills by eva.skills.state.collectAsStateWithLifecycle()
        val skillImport by eva.skillImporter.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { runCatching { eva.memories.load() } }
        val deviceTask by eva.capabilities.deviceTaskFlow.collectAsStateWithLifecycle()
        val webResearch by eva.capabilities.webResearchFlow.collectAsStateWithLifecycle()
        val webResearchNotice by eva.capabilities.webResearchNoticeFlow.collectAsStateWithLifecycle()
        val screenControl by eva.capabilities.screenControlFlow.collectAsStateWithLifecycle()
        val shizuku by shizukuAccess.collectAsStateWithLifecycle()
        val screenControlStatus by eva.screenControl.status.collectAsStateWithLifecycle()
        LaunchedEffect(screenControlStatus) { eva.deviceControlHost?.let { shizukuAccess.value = it.accessStatus() } }
        val repairing by screenControlRepairing.collectAsStateWithLifecycle()
        LaunchedEffect(screenControl, deviceTask, shizuku) {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    eva.screenControl.refresh()
                    delay(SCREEN_CONTROL_REFRESH_MILLIS)
                }
            }
        }
        val extensions by eva.extensions.settings.collectAsStateWithLifecycle()
        val plugins by eva.pluginBrowser.state.collectAsStateWithLifecycle()
        val packages by eva.packageSettings.state.collectAsStateWithLifecycle()
        val waits by eva.packageSettings.waits.collectAsStateWithLifecycle()
        val spotifyClientId by eva.spotify.clientId.collectAsStateWithLifecycle()
        val spotifyAccount by eva.spotify.account.collectAsStateWithLifecycle()
        val spotifyConnect by eva.spotifyConnect.state.collectAsStateWithLifecycle()
        val configuration by eva.configuration.status.collectAsStateWithLifecycle()

        fun hidden(voice: Boolean): Set<String> =
            (prompt as? PromptState.Loaded)
                ?.config
                ?.assemble(
                    com.colonelpanic.eva.conversation.prompt.PromptContext(
                        voice,
                        mapOf("clock" to "", "lookup_retries" to voiceLookupRetries.toString()),
                    ),
                )?.hidden
                .orEmpty()
        val visibleCatalog =
            CatalogAdmission.preview(
                eva.registry.catalog.filterNot { it.id in eva.switchedOffCapabilities() },
                textHidden = hidden(false),
                voiceHidden = hidden(true),
            )
        val stallPeriod by eva.capabilities.stallPeriodFlow.collectAsStateWithLifecycle()
        val verboseLogging by eva.diagnostics.verboseLoggingFlow.collectAsStateWithLifecycle()
        val shownThread by eva.controller.state.collectAsStateWithLifecycle()
        return SettingsUiState(
            verboseLogging = verboseLogging,
            hasCurrentThread = shownThread.threadId != null,
            stallPeriodSeconds = stallPeriod,
            configuration = configuration,
            messaging = messaging,
            messagingApps = messagingApps,
            messagingPermissions = messagingPermissions,
            messagingBridgeChecks = messagingBridgeChecks,
            messagingBridgesNeedingToken =
                remember(messaging.bridges, bridgeCredentialRevision) {
                    eva.messagingSettings
                        .missingBridgeCredentials(messaging.bridges)
                        .map { it.split('/')[1] }
                        .toSet()
                },
            rememberedNumbers = rememberedNumbers,
            memories = memories,
            skills = skills,
            skillImport = skillImport,
            plugins = plugins,
            extensions = extensions,
            packages = packages,
            contentProviders =
                remember(packages, contentPermissionRevision) {
                    packages.flatMap { it.contentAuthorities }.distinct().associateWith { ContentProviderAccess.inspect(this, it) }
                },
            waitDefaults = waits,
            autoEnabled = remember(packages) { eva.packageSettings.autoEnabled() },
            catalogAdmission = visibleCatalog,
            extensionOverflow = CatalogAdmission.overflowReasons(visibleCatalog),
            account = account?.description,
            signIn = signIn,
            hasApiKey = hasApiKey,
            hasHostLink = hasHostLink,
            hasPortalToken = hasPortalToken,
            textModel = textModel,
            realtimeModel = realtimeModel,
            availableTextModels = models[ModelKind.TEXT].orEmpty(),
            availableRealtimeModels = models[ModelKind.REALTIME].orEmpty(),
            reasoningEffort = reasoningEffort,
            voiceReasoningEffort = voiceReasoningEffort,
            voiceLookupRetries = voiceLookupRetries,
            callEndings = callEndings,
            nativeCallEndings =
                eva.registry.catalog
                    .filter { it.source == null && (it.endsVoiceCall != CallEnding.NEVER || it.id in callEndings) }
                    .map { NativeCallEnding(it.id, it.title, it.endsVoiceCall) },
            callMode = (prompt as? PromptState.Loaded)?.config?.callMode,
            isDeviceAssistant = deviceAssistant,
            canSeeMediaSessions = mediaControlAccess,
            canControlScreen = eva.deviceControlHost != null,
            shizukuAccess = shizuku,
            screenControlEnabled = screenControl,
            webResearch = webResearch,
            webResearchNotice = webResearchNotice,
            screenControlStatus = screenControlStatus,
            screenControlRepairing = repairing,
            deviceTask = deviceTask,
            spotifyClientId = spotifyClientId,
            spotifyAccount = spotifyAccount?.description,
            spotifyPremium = spotifyAccount?.product.equals("premium", ignoreCase = true),
            spotifyConnect = spotifyConnect,
            dynamicColor = dynamicColor,
        )
    }

    @Composable
    private fun promptUiState(): PromptUiState {
        val location by eva.prompts.location.collectAsStateWithLifecycle()
        val prompt by eva.prompts.state.collectAsStateWithLifecycle()
        val notice by eva.prompts.notice.collectAsStateWithLifecycle()
        val noticeIsError by eva.prompts.noticeIsError.collectAsStateWithLifecycle()
        val source by eva.prompts.source.collectAsStateWithLifecycle()
        val refreshing by eva.prompts.refreshing.collectAsStateWithLifecycle()
        return PromptUiState(
            location = location,
            prompt = prompt,
            notice = notice,
            noticeIsError = noticeIsError,
            source = source,
            refreshing = refreshing,
        )
    }

    @Composable
    private fun promptActions(): PromptActions =
        remember {
            PromptActions(
                onToggle = { id, enabled -> eva.editPrompt { update { it.toggle(id, enabled) } } },
                onSave = { component -> eva.editPrompt { update { it.upsert(component) } } },
                onDelete = { id -> eva.editPrompt { update { it.remove(id) } } },
                // YAML has no agreed MIME type, so an existing file is found by name rather than by kind.
                onOpenFile = { openPromptFile.launch(arrayOf("*/*")) },
                onCreateFile = { createPromptFile.launch(PromptYaml.FILE_NAME) },
                onUseOwnFile = { eva.editPrompt { useOwnFile() } },
                onReset = { eva.editPrompt { resetToDefaults() } },
                onRefreshSource = { source -> eva.editPrompt { refreshFrom(source) } },
                onDismissNotice = eva.prompts::clearNotice,
            )
        }

    private fun aboutInfo() =
        AboutInfo(
            version = eva.clientVersion,
            versionCode = eva.versionCode,
            applicationId = packageName,
        )

    @Composable
    private fun settingsActions(): SettingsActions =
        remember {
            val settings = eva.settings
            SettingsActions(
                onStallPeriodSeconds = eva.capabilities::saveStallPeriodSeconds,
                onSelectConfigurationFolder = { configurationFolder.launch(null) },
                onReloadConfiguration = eva.configuration::reload,
                onGitEnabled = eva.configuration::setGitEnabled,
                onSaveGit = eva.configuration::configureGit,
                onClearGitToken = eva.configuration::clearGitToken,
                onRepositoryRefresh = eva.pluginBrowser::refreshAll,
                onRepositoryRefreshNew = eva.pluginBrowser::refreshNew,
                onRepositorySync = eva.pluginBrowser::refresh,
                onRepositoryAdd = eva.pluginBrowser::addRepository,
                onRepositoryRemove = eva.pluginBrowser::removeRepository,
                onExtensionAutoEnable = eva.packageSettings::setAutoEnable,
                onPluginFileImport = { pluginFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                onPluginUrlPreview = eva.pluginBrowser::previewUrl,
                onPluginInstall = eva.pluginBrowser::installPreview,
                onPluginRemove = eva.pluginBrowser::remove,
                onSavePackageServer = eva::savePackageServer,
                onClearPackageServer = eva::clearPackageServer,
                onSaveWait = eva.packageSettings::saveWait,
                onExtensionEnable = eva.extensions::enable,
                onExtensionEnableAll = eva.extensions::enableAll,
                onExtensionMutation = eva.extensions::mutation,
                onRefreshExtensions = {
                    contentPermissionRevision++
                    eva.extensions.refresh()
                },
                onContentPermission = { authority ->
                    if (eva.packageSettings.state.value
                            .any { authority in it.contentAuthorities }
                    ) {
                        val access = ContentProviderAccess.inspect(this, authority)
                        if (access.canRequest) contentPermission.launch(requireNotNull(access.permission))
                    }
                },
                onSignIn = eva::startChatGptSignIn,
                onCancelSignIn = eva::cancelChatGptSignIn,
                onSignOut = eva::signOutChatGpt,
                onSaveApiKey = { key -> save { settings.saveApiKey(key) }.also { if (it == null) eva.refreshModels() } },
                onClearApiKey = {
                    settings.clearApiKey()
                    eva.refreshModels()
                },
                onSaveHostLink = { link -> save { settings.saveHostLink(link) } },
                onClearHostLink = settings::clearHostLink,
                onSelectTextModel = { model -> save { settings.saveTextModel(model) } },
                onSelectRealtimeModel = { model -> save { settings.saveRealtimeModel(model) } },
                onSelectReasoningEffort = { effort -> save { settings.saveReasoningEffort(effort) } },
                onSelectVoiceReasoningEffort = { effort -> save { settings.saveVoiceReasoningEffort(effort) } },
                onVoiceLookupRetriesChange = settings::saveVoiceLookupRetries,
                onCallEnding = { id, ending -> save { settings.saveCallEnding(id, ending) } },
                onEndAfterOneRequestChange = { oneRequest ->
                    eva.editPrompt {
                        update { it.selectCallMode(if (oneRequest) VoiceCallMode.ONE_REQUEST else VoiceCallMode.OPEN_CONVERSATION) }
                    }
                },
                onOpenAssistantSettings = ::openAssistantSettings,
                onMessagingEnable = { enabled ->
                    lifecycleScope.launch {
                        eva.registry.changeAuthorization {
                            eva.messagingSettings.enable(enabled)
                            eva.notificationMessages.clear()
                        }
                        EvaNotificationListener
                            .refreshMessages()
                    }
                },
                onMessagingReply = { identity, allowed ->
                    lifecycleScope.launch { eva.registry.changeAuthorization { eva.messagingSettings.allowReply(identity, allowed) } }
                },
                onMessagingRefresh = EvaNotificationListener::refreshMessages,
                onSaveMessagingBridge = {
                    name,
                    label,
                    origin,
                    token,
                    ->
                    save { eva.messagingSettings.saveBridge(name, label, origin, token) }
                },
                onRemoveMessagingBridge = eva.messagingSettings::removeBridge,
                onCheckMessagingBridge = { name -> lifecycleScope.launch { eva.bridgeMessaging.check(name) } },
                onForgetRememberedNumbers = { lifecycleScope.launch { eva.chosenNumbers.forget() } },
                onKeepMemory = { name -> editMemory { eva.memories.keep(name) } },
                onForgetMemory = { name -> editMemory { eva.memories.forget(name) } },
                onSkillEnable = eva.skills::setEnabled,
                onSkillRemove = eva.skills::remove,
                onSkillFetch = eva.skillImporter::fetch,
                onSkillPaste = eva.skillImporter::paste,
                onOpenAppSettings = ::openAppSettings,
                onOpenMediaControlSettings = ::openMediaControlSettings,
                onScreenControlChange = eva.capabilities::saveScreenControl,
                onDeviceTaskChange = eva.capabilities::saveDeviceTask,
                onWebResearchChange = eva.capabilities::saveWebResearch,
                onPortalToken = { token ->
                    eva.capabilities.savePortalToken(token)
                    lifecycleScope.launch { eva.screenControl.refresh() }
                },
                onAllowShizuku = {
                    eva.deviceControlHost?.let { host -> lifecycleScope.launch { shizukuAccess.value = host.requestAccess() } }
                },
                onRepairScreenControl = ::repairScreenControl,
                onSaveSpotifyClientId = { clientId -> save { eva.spotify.saveClientId(clientId) } },
                onConnectSpotify = {
                    eva.spotifyConnect.begin(
                        eva.spotify.clientId.value
                            .orEmpty(),
                    )
                },
                onCancelSpotifyConnect = eva.spotifyConnect::cancel,
                onDisconnectSpotify = {
                    eva.spotifyConnect.cancel()
                    eva.spotify.clear()
                },
                onDynamicColorChange = eva.appearance::saveDynamicColor,
                onVerboseLogging = eva.diagnostics::saveVerboseLogging,
                onExportLogs = { shareDiagnostics { logsExport() } },
                onShareThreadDiagnostics = {
                    eva.controller.state.value.threadId
                        ?.let(::shareThreadDiagnostics)
                },
            )
        }

    private fun repairScreenControl(
        backend: String,
        openSettings: () -> Unit,
    ) {
        if (backend in screenControlRepairing.value) return
        screenControlRepairing.update { it + backend }
        lifecycleScope.launch {
            val outcome =
                try {
                    eva.repairScreenControl(backend)
                } finally {
                    screenControlRepairing.update { it - backend }
                    eva.deviceControlHost?.let { shizukuAccess.value = it.accessStatus() }
                }
            when (outcome) {
                is RepairOutcome.Open -> {
                    try {
                        startActivity(outcome.intent)
                    } catch (_: ActivityNotFoundException) {
                        openSettings()
                    }
                }

                is RepairOutcome.OpenSettings -> {
                    openSettings()
                }

                is RepairOutcome.Fixed, is RepairOutcome.Failed -> {
                    Unit
                }
            }
            Toast.makeText(this@MainActivity, outcome.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareThreadDiagnostics(threadId: String) = shareDiagnostics { threadExport(threadId) }

    private fun shareDiagnostics(export: suspend AndroidDiagnostics.() -> AndroidDiagnostics.Export) {
        lifecycleScope.launch {
            try {
                eva.diagnosticsExport.share(this@MainActivity, eva.diagnosticsExport.export())
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast
                    .makeText(
                        this@MainActivity,
                        "Could not export diagnostics: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation =
            if (resources.configuration.smallestScreenWidthDp >= 600) {
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        enableEdgeToEdge()
        eva.refreshModels()
        val controller = eva.controller
        intent?.getStringExtra(WorkNotifications.EXTRA_THREAD_ID)?.let(controller::showThread)
        if (intent?.getBooleanExtra(WorkNotifications.EXTRA_RUNNING_WORK, false) == true) {
            runningWorkRequest++
            intent.removeExtra(WorkNotifications.EXTRA_RUNNING_WORK)
        }
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val dynamicColor by eva.appearance.dynamicColorFlow.collectAsStateWithLifecycle()
            EvaTheme(dynamicColor = dynamicColor) {
                if (surface.locked) {
                    HandsFreeSurface(
                        state = state,
                        onUnlock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ::requestUnlock else null,
                        onStop = controller::disconnect,
                        onToggleMicrophone = controller::toggleMicrophone,
                        onTogglePlayback = controller::togglePlayback,
                    )
                    return@EvaTheme
                }
                EvaApp(
                    state = state,
                    settings = settingsUiState(dynamicColor),
                    settingsActions = settingsActions(),
                    prompt = promptUiState(),
                    promptActions = promptActions(),
                    about = aboutInfo(),
                    tasks = controller.taskSnapshots,
                    runningWorkRequest = runningWorkRequest,
                    onStopWork = controller::stopTask,
                    onForceStopWork = controller::forceStopTask,
                    onStopAllWork = controller::stopAllTasks,
                    onShareDiagnostics = ::shareThreadDiagnostics,
                    onShowThread = controller::showThread,
                    onStopTask = controller::stopTask,
                    onSubmit = controller::submit,
                    onAnswer = controller::submitAnswer,
                    onConnect = { controller.connect(eva.settings.hostLink()) },
                    onVoice = ::startVoice,
                    onAssistant = ::showAssistant,
                    onDisconnect = controller::disconnect,
                    onToggleMicrophone = controller::toggleMicrophone,
                    onTogglePlayback = controller::togglePlayback,
                    denial = voice.denial,
                    onRetryMicrophone = ::retryMicrophone,
                    onDismissDenial = ::dismissDenial,
                )
            }
        }
        if (!voice.permissionsRequested) {
            voice.permissionsRequested = true
            requestMissingPermissions()
        }
        if (!voice.launchHandled) {
            voice.launchHandled = true
            openHandsFree()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(WorkNotifications.EXTRA_THREAD_ID)?.let(eva.controller::showThread)
        if (intent.getBooleanExtra(WorkNotifications.EXTRA_RUNNING_WORK, false)) {
            runningWorkRequest++
            intent.removeExtra(WorkNotifications.EXTRA_RUNNING_WORK)
        }
        setIntent(intent)
        voice.launchHandled = true
        openHandsFree()
    }

    override fun onResume() {
        super.onResume()
        com.colonelpanic.eva.conversation.TurnWorkService
            .retryUpgrade(this)
        contentPermissionRevision++
        eva.configuration.reloadOnResume()
        eva.extensions.refresh()
        // Both grants are made in system settings, so the answers only change while EVA is away.
        deviceAssistant = AssistantRole.isEva(this)
        mediaControlAccess = MediaControlAccess.isGranted(this)
        messagingPermissions = EvaPermissions.MESSAGING.map { PermissionStatus(permissionLabel(it), EvaPermissions.isGranted(this, it)) }
        EvaNotificationListener
            .refreshMessages()
        if (surface.locked && !isLocked()) surface = Launch.HANDS_FREE
        // The file may have been edited while EVA was away, and its source may have moved on.
        eva.editPrompt {
            reload()
            follow()
        }
        eva.intentHost.attach(this) { permission -> capabilityPermission.launch(permission) }
        if (Build.VERSION.SDK_INT >= 37) eva.shizukuShellHost?.attach(this)
        if (Build.VERSION.SDK_INT >= 30) eva.deviceControlHost?.attach(this)
    }

    override fun onPause() {
        eva.intentHost.detach(this)
        if (Build.VERSION.SDK_INT >= 30) eva.deviceControlHost?.detach(this)
        if (Build.VERSION.SDK_INT >= 37) eva.shizukuShellHost?.detach(this)
        super.onPause()
    }
}

private const val SCREEN_CONTROL_REFRESH_MILLIS = 5_000L

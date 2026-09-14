package com.colonelpanic.eva

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.CatalogAdmission
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.WorkNotifications
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.HandsFreeSurface
import com.colonelpanic.eva.ui.VoiceAccessModel
import com.colonelpanic.eva.ui.VoiceStart
import com.colonelpanic.eva.ui.about.AboutInfo
import com.colonelpanic.eva.ui.prompt.PromptActions
import com.colonelpanic.eva.ui.prompt.PromptUiState
import com.colonelpanic.eva.ui.settings.SettingsActions
import com.colonelpanic.eva.ui.settings.SettingsUiState
import com.colonelpanic.eva.ui.theme.EvaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val voice: VoiceAccessModel by viewModels()
    private var surface by mutableStateOf(Launch.MANUAL)
    private var deviceAssistant by mutableStateOf(false)
    private var mediaControlAccess by mutableStateOf(false)
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
                    checkNotNull(contentResolver.openInputStream(uri)) { "Could not open the selected plugin file" }
                }
            }
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
            is VoiceStart.Connect -> eva.controller.connectVoice(start.link, newThread = surface.startsVoice)
            is VoiceStart.RequestMicrophone -> requestMissingPermissions()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    /** Whichever of the assistant screens this device actually has. */
    private fun openAssistantSettings() = openFirstAvailable(AssistantRole.settingsIntents())

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

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

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
        val textModel by settings.textModelFlow.collectAsStateWithLifecycle()
        val realtimeModel by settings.realtimeModelFlow.collectAsStateWithLifecycle()
        val reasoningEffort by settings.reasoningEffortFlow.collectAsStateWithLifecycle()
        val voiceLookupRetries by settings.voiceLookupRetriesFlow.collectAsStateWithLifecycle()
        val models by eva.availableModels.collectAsStateWithLifecycle()
        val account by eva.chatGpt.account.collectAsStateWithLifecycle()
        val signIn by eva.signIn.state.collectAsStateWithLifecycle()
        val screenControl by eva.capabilities.screenControlFlow.collectAsStateWithLifecycle()
        val extensions by eva.extensions.settings.collectAsStateWithLifecycle()
        val plugins by eva.pluginBrowser.state.collectAsStateWithLifecycle()
        val packages by eva.packageSettings.state.collectAsStateWithLifecycle()
        val waits by eva.packageSettings.waits.collectAsStateWithLifecycle()
        val spotifyClientId by eva.spotify.clientId.collectAsStateWithLifecycle()
        val spotifyAccount by eva.spotify.account.collectAsStateWithLifecycle()
        val spotifyConnect by eva.spotifyConnect.state.collectAsStateWithLifecycle()
        return SettingsUiState(
            plugins = plugins,
            extensions = extensions,
            packages = packages,
            waitDefaults = waits,
            extensionOverflow =
                CatalogAdmission.overflowReasons(
                    eva.registry.catalog.filterNot {
                        !screenControl && it.id in CapabilityRegistry.SCREEN_CONTROL
                    },
                ),
            account = account?.description,
            signIn = signIn,
            hasApiKey = hasApiKey,
            hasHostLink = hasHostLink,
            textModel = textModel,
            realtimeModel = realtimeModel,
            availableTextModels = models[ModelKind.TEXT].orEmpty(),
            availableRealtimeModels = models[ModelKind.REALTIME].orEmpty(),
            reasoningEffort = reasoningEffort,
            voiceLookupRetries = voiceLookupRetries,
            isDeviceAssistant = deviceAssistant,
            canSeeMediaSessions = mediaControlAccess,
            canControlScreen = eva.deviceControlHost != null,
            screenControlEnabled = screenControl,
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
        return PromptUiState(location = location, prompt = prompt, notice = notice)
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
                onRepositoryRefresh = eva.pluginBrowser::refresh,
                onPluginFileImport = { pluginFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                onPluginPreview = eva.pluginBrowser::preview,
                onPluginUrlPreview = eva.pluginBrowser::previewUrl,
                onPluginInstall = eva.pluginBrowser::installPreview,
                onPluginRemove = eva.pluginBrowser::remove,
                onSavePackageServer = eva::savePackageServer,
                onClearPackageServer = eva::clearPackageServer,
                onSaveWait = eva.packageSettings::saveWait,
                onExtensionEnable = eva.extensions::enable,
                onExtensionMutation = eva.extensions::mutation,
                onRefreshExtensions = eva.extensions::refresh,
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
                onVoiceLookupRetriesChange = settings::saveVoiceLookupRetries,
                onOpenAssistantSettings = ::openAssistantSettings,
                onOpenMediaControlSettings = ::openMediaControlSettings,
                onScreenControlChange = eva.capabilities::saveScreenControl,
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
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        eva.refreshModels()
        val controller = eva.controller
        intent?.getStringExtra(WorkNotifications.EXTRA_THREAD_ID)?.let(controller::showThread)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val threads by controller.threads.collectAsStateWithLifecycle()
            val dynamicColor by eva.appearance.dynamicColorFlow.collectAsStateWithLifecycle()
            EvaTheme(dynamicColor = dynamicColor) {
                if (surface.locked) {
                    HandsFreeSurface(
                        state = state,
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
                    threads = threads,
                    onNewThread = controller::newThread,
                    onShowThread = controller::showThread,
                    onStopTask = controller::stopTask,
                    onSubmit = controller::submit,
                    onConnect = { controller.connect(eva.settings.hostLink()) },
                    onVoice = ::startVoice,
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
        setIntent(intent)
        voice.launchHandled = true
        openHandsFree()
    }

    override fun onResume() {
        super.onResume()
        eva.extensions.refresh()
        // Both grants are made in system settings, so the answers only change while EVA is away.
        deviceAssistant = AssistantRole.isEva(this)
        mediaControlAccess = MediaControlAccess.isGranted(this)
        if (surface.locked && !isLocked()) surface = Launch.HANDS_FREE
        // The file may have been edited while EVA was away.
        eva.editPrompt { reload() }
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

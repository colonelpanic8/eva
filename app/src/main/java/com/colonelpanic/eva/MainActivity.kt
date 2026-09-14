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
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.HandsFreeSurface
import com.colonelpanic.eva.ui.VoiceAccessModel
import com.colonelpanic.eva.ui.VoiceStart
import com.colonelpanic.eva.ui.about.AboutInfo
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

    private val capabilityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> eva.intentHost.onPermissionResult(granted) }

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
            is VoiceStart.Connect -> eva.controller.connectVoice(start.link)
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
        return SettingsUiState(
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
            dynamicColor = dynamicColor,
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
                onDynamicColorChange = eva.appearance::saveDynamicColor,
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        eva.refreshModels()
        val controller = eva.controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
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
                    about = aboutInfo(),
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
        setIntent(intent)
        voice.launchHandled = true
        openHandsFree()
    }

    override fun onResume() {
        super.onResume()
        // Both grants are made in system settings, so the answers only change while EVA is away.
        deviceAssistant = AssistantRole.isEva(this)
        mediaControlAccess = MediaControlAccess.isGranted(this)
        if (surface.locked && !isLocked()) surface = Launch.HANDS_FREE
        eva.intentHost.attach(this) { permission -> capabilityPermission.launch(permission) }
        if (Build.VERSION.SDK_INT >= 37) eva.shizukuShellHost?.attach(this)
    }

    override fun onPause() {
        eva.intentHost.detach(this)
        if (Build.VERSION.SDK_INT >= 37) eva.shizukuShellHost?.detach(this)
        super.onPause()
    }
}

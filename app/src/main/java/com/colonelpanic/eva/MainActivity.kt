package com.colonelpanic.eva

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.HandsFreeSurface
import com.colonelpanic.eva.ui.VoiceAccessModel
import com.colonelpanic.eva.ui.VoiceStart
import com.colonelpanic.eva.ui.theme.EvaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val voice: VoiceAccessModel by viewModels()
    private var surface by mutableStateOf(Launch.MANUAL)
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val canAskAgain = granted || shouldShowRequestPermissionRationale(MicrophonePermission.PERMISSION)
            voice.update { onPermissionResult(granted, canAskAgain) }?.let(::perform)
        }

    private val eva get() = application as EvaApplication

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val capabilityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> eva.intentHost.onPermissionResult(granted) }

    private fun startVoice(
        link: String,
        listenOnly: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        perform(voice.update { start(link, listenOnly, MicrophonePermission.isGranted(this@MainActivity)) })
    }

    private fun retryMicrophone() {
        val next = voice.update { retry(MicrophonePermission.isGranted(this@MainActivity)) }
        if (next == null && voice.denial != null) openAppSettings() else next?.let(::perform)
    }

    private fun listenOnlyInstead() {
        voice.update { listenOnlyInstead() }?.let(::perform)
    }

    private fun dismissDenial() {
        voice.update { dismiss() }
    }

    private fun perform(start: VoiceStart) {
        when (start) {
            is VoiceStart.Connect -> eva.controller.connectVoice(start.link, start.listenOnly)
            is VoiceStart.RequestMicrophone -> microphonePermission.launch(MicrophonePermission.PERMISSION)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
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
            startVoice(link = "", listenOnly = false)
        }
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        eva.refreshModels()
        val controller = eva.controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val hasApiKey by eva.settings.hasApiKey.collectAsStateWithLifecycle()
            val textModel by eva.settings.textModelFlow.collectAsStateWithLifecycle()
            val realtimeModel by eva.settings.realtimeModelFlow.collectAsStateWithLifecycle()
            val reasoningEffort by eva.settings.reasoningEffortFlow.collectAsStateWithLifecycle()
            val voiceLookupRetries by eva.settings.voiceLookupRetriesFlow.collectAsStateWithLifecycle()
            val models by eva.availableModels.collectAsStateWithLifecycle()
            val account by eva.chatGpt.account.collectAsStateWithLifecycle()
            val signIn by eva.signIn.state.collectAsStateWithLifecycle()
            EvaTheme {
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
                    onSubmit = controller::submit,
                    onConnect = controller::connect,
                    onDisconnect = controller::disconnect,
                    onVoice = ::startVoice,
                    hasApiKey = hasApiKey,
                    onSaveApiKey = { key ->
                        runCatching { eva.settings.saveApiKey(key) }.onSuccess { eva.refreshModels() }
                    },
                    onClearApiKey = {
                        eva.settings.clearApiKey()
                        eva.refreshModels()
                    },
                    account = account?.description,
                    signIn = signIn,
                    onSignIn = eva::startChatGptSignIn,
                    onCancelSignIn = eva::cancelChatGptSignIn,
                    onSignOut = eva::signOutChatGpt,
                    textModel = textModel,
                    realtimeModel = realtimeModel,
                    availableTextModels = models[ModelKind.TEXT].orEmpty(),
                    availableRealtimeModels = models[ModelKind.REALTIME].orEmpty(),
                    onSelectTextModel = { model -> runCatching { eva.settings.saveTextModel(model) } },
                    onSelectRealtimeModel = { model -> runCatching { eva.settings.saveRealtimeModel(model) } },
                    reasoningEffort = reasoningEffort,
                    onSelectReasoningEffort = { effort -> runCatching { eva.settings.saveReasoningEffort(effort) } },
                    voiceLookupRetries = voiceLookupRetries,
                    onVoiceLookupRetriesChange = eva.settings::saveVoiceLookupRetries,
                    onToggleMicrophone = controller::toggleMicrophone,
                    onTogglePlayback = controller::togglePlayback,
                    denial = voice.denial,
                    onRetryMicrophone = ::retryMicrophone,
                    onListenOnlyInstead = ::listenOnlyInstead,
                    onDismissDenial = ::dismissDenial,
                )
            }
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

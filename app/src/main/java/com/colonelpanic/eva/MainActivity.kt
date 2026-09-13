package com.colonelpanic.eva

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.providers.openai.ModelKind
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.VoiceAccessModel
import com.colonelpanic.eva.ui.VoiceStart
import com.colonelpanic.eva.ui.theme.EvaTheme

class MainActivity : ComponentActivity() {
    private val voice: VoiceAccessModel by viewModels()
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val canAskAgain = granted || shouldShowRequestPermissionRationale(MicrophonePermission.PERMISSION)
            voice.update { onPermissionResult(granted, canAskAgain) }?.let(::perform)
        }

    private val eva get() = application as EvaApplication

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
            val models by eva.availableModels.collectAsStateWithLifecycle()
            EvaTheme {
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
                    textModel = textModel,
                    realtimeModel = realtimeModel,
                    availableTextModels = models[ModelKind.TEXT].orEmpty(),
                    availableRealtimeModels = models[ModelKind.REALTIME].orEmpty(),
                    onSelectTextModel = { model -> runCatching { eva.settings.saveTextModel(model) } },
                    onSelectRealtimeModel = { model -> runCatching { eva.settings.saveRealtimeModel(model) } },
                    onToggleMicrophone = controller::toggleMicrophone,
                    onTogglePlayback = controller::togglePlayback,
                    denial = voice.denial,
                    onRetryMicrophone = ::retryMicrophone,
                    onListenOnlyInstead = ::listenOnlyInstead,
                    onDismissDenial = ::dismissDenial,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        eva.intentHost.attach(this)
    }

    override fun onPause() {
        eva.intentHost.detach(this)
        super.onPause()
    }
}

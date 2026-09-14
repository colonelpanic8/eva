package com.colonelpanic.eva.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.R
import com.colonelpanic.eva.adapters.android.AssistantLauncher
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.ui.AssistantSurface
import com.colonelpanic.eva.ui.theme.EvaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * EVA's panel over the foreground app when invoked through the assistant role.
 *
 * Screen context is deliberately not read: `onHandleAssist` and `onHandleScreenshot` stay
 * unimplemented, so nothing about the app underneath reaches a provider.
 */
class EvaVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context) {
    private val eva = context.applicationContext as EvaApplication
    private val owners = SessionViewOwners()
    private var startJob: Job? = null
    private var hangUpJob: Job? = null
    private var locked by mutableStateOf(false)
    private var needsMicrophone by mutableStateOf(false)

    private val assistantLauncher =
        AssistantLauncher { intent ->
            // Older releases use the visible session window as their launch surface.
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startAssistantActivity(launch) else context.startActivity(launch)
        }

    init {
        setTheme(R.style.Theme_EVA_Assistant)
    }

    override fun onCreate() {
        super.onCreate()
        owners.create()
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context)
        owners.own(view)
        view.setContent {
            val state by eva.controller.state.collectAsStateWithLifecycle()
            val dynamicColor by eva.appearance.dynamicColorFlow.collectAsStateWithLifecycle()
            EvaTheme(dynamicColor = dynamicColor) {
                AssistantSurface(
                    state = state,
                    locked = locked,
                    needsMicrophone = needsMicrophone,
                    onStart = ::start,
                    onStop = eva.controller::disconnect,
                    onToggleMicrophone = eva.controller::toggleMicrophone,
                    onTogglePlayback = eva.controller::togglePlayback,
                    onOpenApp = ::openApp,
                    onDismiss = ::hide,
                )
            }
        }
        return view
    }

    override fun onShow(
        args: Bundle?,
        showFlags: Int,
    ) {
        super.onShow(args, showFlags)
        locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        owners.show()
        eva.intentHost.attachAssistant(assistantLauncher)
        watchHangUp()
        start()
    }

    override fun onLockscreenShown() {
        super.onLockscreenShown()
        locked = true
    }

    override fun onHide() {
        eva.intentHost.detachAssistant(assistantLauncher)
        startJob?.cancel()
        hangUpJob?.cancel()
        owners.hide()
        super.onHide()
    }

    override fun onDestroy() {
        eva.intentHost.detachAssistant(assistantLauncher)
        hangUpJob?.cancel()
        owners.destroy()
        super.onDestroy()
    }

    /**
     * The panel exists only to host the call, so the model hanging up takes it down and returns
     * the user to whatever it was covering, including an app an action just launched. Only this
     * panel closes: EVA's own activity is a separate window and is left where the user put it.
     */
    private fun watchHangUp() {
        hangUpJob?.cancel()
        hangUpJob = owners.lifecycleScope.launch { eva.controller.hangUps.collect { hide() } }
    }

    /**
     * Stored conversations have to finish loading before the controller accepts a connection,
     * and a session already running is left alone: the panel controls it instead of replacing it.
     */
    private fun start() {
        startJob?.cancel()
        startJob =
            owners.lifecycleScope.launch {
                val loaded = eva.controller.state.first { !it.isLoading }
                val next = assistantStart(MicrophonePermission.isGranted(context), loaded.voiceMode, loaded.providerStatus)
                needsMicrophone = next == AssistantStart.NEEDS_MICROPHONE
                if (next == AssistantStart.CONNECT) eva.controller.connectVoice(eva.settings.hostLink(), newThread = true)
            }
    }

    /** The panel is on screen, so EVA has a visible window and its own task will accept this. */
    private fun openApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }
}

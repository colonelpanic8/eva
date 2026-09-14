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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * EVA's surface when the system, not the launcher, opened it. The panel is drawn over
 * whatever app is in front rather than replacing it, and while it exists EVA can open an
 * app for a spoken request even with no screen of its own, which is the gap an activity
 * launch leaves behind once another app takes the foreground.
 *
 * Screen context is deliberately not read: `onHandleAssist` and `onHandleScreenshot` stay
 * unimplemented, so nothing about the app underneath reaches a provider.
 */
class EvaVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context) {
    private val eva = context.applicationContext as EvaApplication
    private val owners = SessionViewOwners()
    private var locked by mutableStateOf(false)
    private var needsMicrophone by mutableStateOf(false)

    private val assistantLauncher =
        AssistantLauncher { intent ->
            // No activity is starting this, so it has to be its own task. From Android 10 the
            // system only accepts it through the session, which is what survives losing the screen.
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startAssistantActivity(launch) else context.startActivity(launch)
        }

    init {
        setTheme(R.style.Theme_EVA_Assistant)
    }

    override fun onCreate() {
        super.onCreate()
        owners.create()
        // Attached for the session's whole life, not just while shown: a spoken request can
        // land after the panel is dismissed, and that is exactly when an activity cannot help.
        eva.intentHost.attachAssistant(assistantLauncher)
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
        start()
    }

    override fun onLockscreenShown() {
        super.onLockscreenShown()
        locked = true
    }

    override fun onHide() {
        owners.hide()
        super.onHide()
    }

    override fun onDestroy() {
        eva.intentHost.detachAssistant(assistantLauncher)
        owners.destroy()
        super.onDestroy()
    }

    /**
     * Stored conversations have to finish loading before the controller accepts a connection,
     * and a session already running is left alone: the panel controls it instead of replacing it.
     */
    private fun start() {
        owners.lifecycleScope.launch {
            val loaded = eva.controller.state.first { !it.isLoading }
            val next = assistantStart(MicrophonePermission.isGranted(context), loaded.voiceMode, loaded.providerStatus)
            needsMicrophone = next == AssistantStart.NEEDS_MICROPHONE
            if (next == AssistantStart.CONNECT) eva.controller.connectVoice(eva.settings.hostLink())
        }
    }

    /** The panel is on screen, so EVA has a visible window and its own task will accept this. */
    private fun openApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }
}

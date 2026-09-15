package com.colonelpanic.eva.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.speech.RecognizerIntent
import com.colonelpanic.eva.MainActivity
import java.lang.ref.WeakReference

/**
 * The component the system binds while EVA is the selected digital assistant. Declaring
 * `ACTION_ASSIST` on an activity only makes EVA an assist target; holding the role is what
 * puts EVA behind the assist gesture and the power-button hold, and what lets a session
 * answer over another app instead of taking the screen from it.
 *
 * Android keeps the service bound for as long as EVA holds the role, which also keeps
 * [com.colonelpanic.eva.EvaApplication] and its controller resident. The main activity retains
 * only a weak route back to that system-owned service so its test button can show the same panel.
 */
class EvaVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE)
                .putExtra(RecognizerIntent.EXTRA_SECURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onReady() {
        super.onReady()
        active = WeakReference(this)
    }

    override fun onShutdown() {
        if (active.get() === this) active.clear()
        super.onShutdown()
    }

    companion object {
        private var active = WeakReference<EvaVoiceInteractionService>(null)

        fun showAssistant(oneShot: Boolean = false): Boolean {
            val service = active.get() ?: return false
            service.showSession(Bundle().apply { putBoolean(EXTRA_ONE_SHOT, oneShot) }, 0)
            return true
        }

        internal const val EXTRA_ONE_SHOT = "com.colonelpanic.eva.extra.ONE_SHOT"
    }
}

/**
 * Whether EVA holds the role, and the one screen that can change it. Android does not expose
 * the assistant role to `RoleManager.createRequestRoleIntent` the way it does the default
 * dialer or SMS app, so the user has to pick EVA in system settings.
 */
object AssistantRole {
    fun isEva(context: Context): Boolean =
        runCatching {
            VoiceInteractionService.isActiveService(context, ComponentName(context, EvaVoiceInteractionService::class.java))
        }.getOrDefault(false)

    /** Assist and voice input, falling back to the settings root where that screen is absent. */
    fun settingsIntents(): List<Intent> =
        listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
}

package com.colonelpanic.eva.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/**
 * The component the system binds while EVA is the selected digital assistant. Declaring
 * `ACTION_ASSIST` on an activity only makes EVA an assist target; holding the role is what
 * puts EVA behind the assist gesture and the power-button hold, and what lets a session
 * answer over another app instead of taking the screen from it.
 *
 * The service itself has no work to do. Android keeps it bound for as long as EVA holds the
 * role, which also keeps [com.colonelpanic.eva.EvaApplication] and its controller resident.
 */
class EvaVoiceInteractionService : VoiceInteractionService()

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

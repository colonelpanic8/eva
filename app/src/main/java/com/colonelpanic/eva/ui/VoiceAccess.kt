package com.colonelpanic.eva.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

sealed interface VoiceStart {
    data class Connect(
        val link: String,
    ) : VoiceStart

    data class RequestMicrophone(
        val link: String,
    ) : VoiceStart
}

data class MicrophoneDenial(
    val link: String,
    val canAskAgain: Boolean,
)

/** Microphone-permission flow. Links are broker access codes and stay in memory only. */
class VoiceAccess {
    var denial: MicrophoneDenial? = null
        private set
    private var requested: String? = null

    fun start(
        link: String,
        microphoneGranted: Boolean,
    ): VoiceStart {
        denial = null
        if (microphoneGranted) {
            requested = null
            return VoiceStart.Connect(link)
        }
        requested = link
        return VoiceStart.RequestMicrophone(link)
    }

    /** Null when the user declined; the denial is then pending. */
    fun onPermissionResult(
        granted: Boolean,
        canAskAgain: Boolean,
    ): VoiceStart.Connect? {
        val link = requested ?: return null
        requested = null
        if (granted) return VoiceStart.Connect(link)
        denial = MicrophoneDenial(link, canAskAgain)
        return null
    }

    /** Null with a pending denial means the system will not ask again; open settings. */
    fun retry(microphoneGranted: Boolean): VoiceStart? {
        val pending = denial ?: return null
        if (microphoneGranted) {
            denial = null
            return VoiceStart.Connect(pending.link)
        }
        if (!pending.canAskAgain) return null
        denial = null
        requested = pending.link
        return VoiceStart.RequestMicrophone(pending.link)
    }

    fun dismiss() {
        denial = null
        requested = null
    }
}

/** Activity-retained, never saved: survives rotation, not process death. */
class VoiceAccessModel : ViewModel() {
    val access = VoiceAccess()

    /** Retained so a rotation does not reopen the microphone for a launch already answered. */
    var launchHandled = false

    /** The launch sweep asks for everything once per Activity, not once per rotation. */
    var permissionsRequested = false

    /** A second launch while a dialog is open loses the answer, so callers wait for this one. */
    var requestInFlight = false
    var denial by mutableStateOf<MicrophoneDenial?>(null)
        private set

    fun <T> update(action: VoiceAccess.() -> T): T = access.action().also { denial = access.denial }
}

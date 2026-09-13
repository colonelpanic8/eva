package com.colonelpanic.eva.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/** Voice-call focus, communication mode, and speaker routing for one session. */
internal class AndroidAudioRoute(
    private val audioManager: AudioManager,
) : AudioRoutePort {
    private var request: AudioFocusRequest? = null
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone: Boolean? = null
    private var held = false

    @Synchronized
    override fun acquire(
        speakerphone: Boolean,
        onFocusChange: (AudioFocusState) -> Unit,
    ): Boolean {
        if (held) return true
        val focusListener =
            AudioManager.OnAudioFocusChangeListener { change ->
                onFocusChange(
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusState.HELD
                        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusState.LOST
                        else -> AudioFocusState.TRANSIENT_LOSS
                    },
                )
            }
        val granted = requestFocus(focusListener) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false
        listener = focusListener
        held = true
        try {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (speakerphone) routeToSpeaker()
        } catch (error: RuntimeException) {
            release()
            throw error
        }
        return true
    }

    @Synchronized
    override fun release() {
        if (!held) return
        held = false
        restoreRoute()
        audioManager.mode = previousMode
        abandonFocus()
        request = null
        listener = null
    }

    private fun requestFocus(focusListener: AudioManager.OnAudioFocusChangeListener): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val built =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setOnAudioFocusChangeListener(focusListener)
                    .build()
            request = built
            audioManager.requestAudioFocus(built)
        } else {
            legacyRequestFocus(focusListener)
        }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            listener?.let { legacyAbandonFocus(it) }
        }
    }

    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            legacySpeakerphone(true)
        }
    }

    private fun restoreRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            previousSpeakerphone?.let { legacySpeakerphone(it) }
        }
        previousSpeakerphone = null
    }

    @Suppress("DEPRECATION")
    private fun legacyRequestFocus(focusListener: AudioManager.OnAudioFocusChangeListener): Int =
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)

    @Suppress("DEPRECATION")
    private fun legacyAbandonFocus(focusListener: AudioManager.OnAudioFocusChangeListener) {
        audioManager.abandonAudioFocus(focusListener)
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphone(enabled: Boolean) {
        if (previousSpeakerphone == null) previousSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = enabled
    }
}

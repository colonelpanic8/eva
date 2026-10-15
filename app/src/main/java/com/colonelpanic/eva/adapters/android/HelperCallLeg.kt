package com.colonelpanic.eva.adapters.android

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** How the platform reaches a call's audio, which decides what the owner hears and how their microphone is routed. */
internal enum class CallRedirect {
    /**
     * Cellular: the telephony HAL taps the call. The owner keeps hearing the other party, and the
     * injection is mixed with the phone's microphone, so the agent's floor mutes that microphone.
     */
    PSTN,

    /**
     * VoIP: an audio policy loopback of the app's voice-communication playback and capture. The
     * other party no longer plays on the phone, so EVA plays them on the call's output device, and
     * the injection replaces the app's microphone, so the owner's floor stops injecting.
     */
    VOIP,
}

/**
 * Call audio redirected through the Shizuku call-audio helper: the remote side arrives on the
 * helper's socket and the uplink is written back to it. The microphone routing follows [redirect];
 * since Android's microphone mute is global, closing a cellular path unmutes it.
 *
 * The helper runs as shell because only privileged callers may capture TELEPHONY_RX, inject with
 * USAGE_CALL_ASSISTANT, or register a VoIP call-extraction loopback. One socket carries both
 * directions at [EngineCallLeg.SAMPLE_RATE]; reads block on the helper, which is fed by the platform
 * in real time, so the downlink needs no clock of its own here.
 */
internal class HelperAudioPath(
    private val helper: ICallAudio,
    private val redirect: CallRedirect,
) : CallAudioPath {
    override val ownerHearsRemote = redirect == CallRedirect.PSTN

    /**
     * On the Pixel a cellular downlink capture includes EVA's own local playback (a HAL-level capture no
     * playback attribute excludes), so while listen-in plays its echo is gated out of what the agent
     * hears (see [CallMonitorPlayback]). A VoIP redirection pins EVA's playback outside its loopback
     * and likely does not need this; it keeps the gate as a safeguard because that is not yet
     * device-verified.
     */
    override val capturesLocalPlayback = true

    private var socket: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null

    /** The floor's microphone routing, applied to a VoIP injection once audio opens. */
    @Volatile private var ownerToRemote = false

    override fun open() {
        val opened = helper.openAudio(EngineCallLeg.SAMPLE_RATE)
        socket = opened
        input = FileInputStream(opened.fileDescriptor)
        output = FileOutputStream(opened.fileDescriptor)
        if (redirect == CallRedirect.VOIP && ownerToRemote) helper.setUplinkInjection(false)
    }

    override fun readRemote(frame: ByteArray): Boolean {
        val input = input ?: return false
        var filled = 0
        while (filled < frame.size) {
            val read =
                try {
                    input.read(frame, filled, frame.size - filled)
                } catch (_: IOException) {
                    -1
                }
            if (read < 0) return false
            filled += read
        }
        return true
    }

    override fun writeUplink(frame: ByteArray): Boolean =
        try {
            checkNotNull(output).write(frame)
            true
        } catch (_: IOException) {
            false
        }

    /**
     * PSTN: the injection is mixed with the phone's microphone, so the agent's floor mutes that
     * microphone (globally; telephony can reset it, which is why the coordinator reapplies routing at
     * every live leg state). VOIP: the injection replaces the app's microphone, so the owner's floor
     * stops injecting and the calling app hears the real microphone again.
     */
    override fun routeOwnerMicrophone(toRemote: Boolean) {
        ownerToRemote = toRemote
        when (redirect) {
            CallRedirect.PSTN -> helper.setMicrophoneMuted(!toRemote)
            CallRedirect.VOIP -> if (socket != null) helper.setUplinkInjection(!toRemote)
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        if (redirect == CallRedirect.PSTN) runCatching { helper.setMicrophoneMuted(false) }
    }
}

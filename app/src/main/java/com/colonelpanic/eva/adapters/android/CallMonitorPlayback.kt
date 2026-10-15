package com.colonelpanic.eva.adapters.android

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.colonelpanic.eva.call.CallEchoCanceller

/**
 * Listen-in for a cellular call task: plays what EVA injects into the call's uplink (the agent's
 * voice and keypad tones) on the phone itself, so the owner hears the agent alongside the other
 * party, whose audio the phone already plays natively. Nothing written here reaches the uplink.
 *
 * The echo problem. On the Pixel 11 Pro Fold this local playback shows up in the call's downlink
 * capture: the TELEPHONY_RX capture EVA reads through the Shizuku helper is taken in the audio HAL,
 * not from the framework's playback mix, so no playback attribute or usage keeps it out. Without
 * countermeasures the agent hears itself and answers, or its own words trip the server's voice
 * detection and barge in on (cancel) its own reply.
 *
 * The decision: while listen-in plays, [clean] silences every captured frame that may hold echo of
 * recent playback (until about 1 s after it stops; see [CallEchoCanceller.process]). Device evidence
 * for a gate rather than cancellation: the NLMS canceller reached only 0-21 dB of removal on NIST
 * calls, the residual at an 18 dB threshold reached the agent as a remote "Eight." while it counted
 * aloud, and on a Twilio hotline call a residual after a 25 dB pass was detected as remote speech
 * and cut the agent off mid-sentence twice. The canceller still runs, for its logged measurements
 * (`EvaCallMonitor`), so a better approach can be judged on real paths.
 *
 * The cost, and why it is scoped: while the gate is engaged the other party cannot interrupt the
 * agent. So the gate exists only while this playback exists, that is while listen-in is on for the
 * call; with it off, [clean] returns capture untouched and the agent hears everything. Listen-in is
 * therefore off by default (`capabilities.callAgent.listenInByDefault`). A way to listen in without
 * losing barge-in remains open.
 *
 * Routing: the track uses USAGE_VOICE_COMMUNICATION, which Android places on the call's voice output,
 * so it follows the call's route; on the device it moved from the earpiece to the speaker when the
 * owner switched the call to speaker. Bluetooth and wired headsets have not been tested.
 *
 * VoIP calls (the WhatsApp transport) reuse this class to play the other party too, since their
 * audio redirection silences the app's own playback. Such a redirection would capture any
 * voice-communication playback, so there [device] pins the track to the call's own output device,
 * which bypasses the loopback. Routes whose audio comes straight from an SDK (Twilio) never capture
 * this playback and do not route their audio through [clean].
 */
internal class CallMonitorPlayback(
    private val sampleRateHz: Int,
    private val device: (() -> AudioDeviceInfo?)? = null,
    /** False for the other party's local playback, which never gates capture and so logs nothing about a gate. */
    private val listenIn: Boolean = true,
) {
    @Volatile private var track: AudioTrack? = null
    private val echo = CallEchoCanceller(sampleRateHz)
    private var loggedConverged = false
    private var loggedAtNanos = 0L

    /** Whether "capture passes untouched" was logged since listen-in was last on: once per stretch, not per frame. */
    @Volatile private var loggedUngated = false

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (!enabled) return release()
        if (track != null) return
        echo.reset()
        track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(
                    // At least 200 ms, so the paced 20 ms uplink frames do not find it full in normal use.
                    maxOf(
                        AudioTrack.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                        sampleRateHz / 5 * 2,
                    ),
                ).build()
                .also { track ->
                    device?.invoke()?.let(track::setPreferredDevice)
                    track.play()
                }
        loggedUngated = false
        if (listenIn) Log.i(TAG, "Listen-in on: the agent's input is silenced while this playback may echo")
    }

    /**
     * Called by the uplink writer with every frame it sends. Never blocks the uplink's pacing: audio
     * that does not fit is dropped from the monitor only. The canceller is told exactly which samples
     * the track accepted, since only those can echo.
     */
    @Synchronized
    fun write(pcm: ByteArray) {
        val playing = track ?: return
        val accepted = playing.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0) / 2
        echo.played(ShortArray(accepted) { sample(pcm, it) }, accepted)
    }

    /**
     * Applies the listen-in gate to one captured downlink frame, in place, before the agent hears it.
     * With listen-in off there is no playback to echo, so the frame is left exactly as captured.
     */
    fun clean(pcm: ByteArray) {
        if (track == null) {
            if (!loggedUngated) {
                loggedUngated = true
                Log.i(TAG, "Listen-in off: the agent hears all capture, ungated")
            }
            return
        }
        val frame = ShortArray(pcm.size / 2) { sample(pcm, it) }
        if (echo.process(frame)) frame.fill(0)
        val now = System.nanoTime()
        if (echo.converged != loggedConverged || now - loggedAtNanos > 5_000_000_000L) {
            loggedConverged = echo.converged
            loggedAtNanos = now
            Log.i(TAG, "Monitor echo (gate engaged; canceller only measured): ${echo.status}")
        }
        frame.forEachIndexed { i, value ->
            pcm[2 * i] = value.toByte()
            pcm[2 * i + 1] = (value.toInt() shr 8).toByte()
        }
    }

    @Synchronized
    fun release() {
        val playing = track ?: return
        runCatching { playing.stop() }
        playing.release()
        track = null
        if (listenIn) Log.i(TAG, "Listen-in off: the gate is released")
    }

    private fun sample(
        pcm: ByteArray,
        i: Int,
    ) = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()

    private companion object {
        const val TAG = "EvaCallMonitor"
    }
}

package com.colonelpanic.eva.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.colonelpanic.eva.providers.grok.GrokVoiceConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Grok connection's settings and PCM devices, fixed before network setup begins. */
class AndroidPcmVoiceMedia(
    private val context: Context,
    val configuration: GrokVoiceConfiguration,
) : PcmVoiceMedia {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val route = AndroidAudioRoute(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private val cues = ToneVoiceCues()
    private val mutableState = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
    private val mutableControls = MutableStateFlow(MediaControls())
    override val state = mutableState.asStateFlow()
    override val controls = mutableControls.asStateFlow()
    override val timeline = MutableStateFlow(MediaTimeline()).asStateFlow()
    private val notifications = Channel<PcmVoiceMedia.Playback>(Channel.UNLIMITED)
    override val playback = notifications.receiveAsFlow()
    private val queue = Channel<Chunk>(Channel.UNLIMITED)

    private data class Chunk(
        val id: String,
        val bytes: ByteArray?,
        val epoch: Long,
    )

    private val ledger = PcmPlaybackLedger(RATE)
    private var epoch = 0L
    private var closed = false
    private var started = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    override suspend fun start(onAudio: (ByteArray) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                synchronized(gate) {
                    check(!closed && !started) { "PCM voice session is no longer idle." }
                    if (!MicrophonePermission.isGranted(context)) throw MediaException(MediaFailure.MicrophonePermissionRequired)
                    mutableState.value = RealtimeMediaState.Preparing
                    if (!route.acquire(true) { focus -> mutableControls.update { it.copy(focus = focus) } }) {
                        throw MediaException(MediaFailure.AudioFocusDenied)
                    }
                    mutableControls.update { it.copy(focus = AudioFocusState.HELD) }
                    val captureSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val playbackSize = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(captureSize > 0 && playbackSize > 0) { "This device does not support 24 kHz PCM voice." }
                    val recorder =
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(captureSize, 960 * 4),
                        )
                    record = recorder
                    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize the Grok microphone." }
                    if (AcousticEchoCanceler.isAvailable()) {
                        echo =
                            AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
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
                                    .setSampleRate(RATE)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            ).setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(playbackSize, 960 * 4))
                            .build()
                    check(track?.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize Grok audio playback." }
                    track!!.setVolume(if (controls.value.playbackMuted) 0f else 1f)
                    track!!.play()
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The Grok microphone did not start recording." }
                    started = true
                    mutableState.value = RealtimeMediaState.Connected(remoteAudio = false)
                    cues.play(VoiceCue.Started)
                }
                scope.launch { capture(onAudio) }
                scope.launch { play() }
                Unit
            } catch (error: Exception) {
                fail(error)
                throw error
            }
        }

    private suspend fun capture(onAudio: (ByteArray) -> Unit) {
        val buffer = ByteArray(960)
        try {
            while (scope.isActive) {
                val count =
                    synchronized(gate) {
                        if (closed) return
                        record!!.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    }
                check(count >= 0) { "Grok microphone read failed ($count)." }
                if (count > 0) {
                    onAudio(if (controls.value.microphoneMuted) ByteArray(count) else buffer.copyOf(count))
                } else {
                    delay(5)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun enqueue(
        responseId: String,
        audio: ByteArray,
    ) = synchronized(gate) {
        check(!closed) { "Grok playback is closed." }
        require(audio.size % 2 == 0) { "Grok returned an incomplete PCM16 sample." }
        if (audio.isNotEmpty()) mutableState.value = RealtimeMediaState.Connected(remoteAudio = true)
        ledger.enqueue(responseId)
        queue.trySend(Chunk(responseId, audio, epoch))
        Unit
    }

    override fun finish(responseId: String) =
        synchronized(gate) {
            queue.trySend(Chunk(responseId, null, epoch))
            Unit
        }

    private suspend fun play() {
        try {
            for (chunk in queue) {
                if (chunk.bytes == null) {
                    while (scope.isActive) {
                        val done =
                            synchronized(gate) {
                                if (closed || chunk.epoch != epoch) {
                                    true
                                } else {
                                    val played = ledger.finish(chunk.id, head())
                                    if (played != null) {
                                        notifications.trySend(PcmVoiceMedia.Playback.Finished(chunk.id, played))
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                        if (done) break
                        delay(5)
                    }
                } else {
                    var offset = 0
                    while (offset < chunk.bytes.size && scope.isActive) {
                        val count =
                            synchronized(gate) {
                                if (closed || chunk.epoch != epoch) {
                                    -1
                                } else {
                                    val count = track!!.write(chunk.bytes, offset, chunk.bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                    check(count >= 0) { "Grok audio playback failed ($count)." }
                                    if (ledger.write(chunk.id, count / 2, !controls.value.playbackMuted)) {
                                        notifications.trySend(PcmVoiceMedia.Playback.Started(chunk.id))
                                    }
                                    count
                                }
                            }
                        if (count < 0) break
                        offset += count
                        if (count == 0) delay(5)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun head(): Long = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0

    override fun interrupt(): Map<String, Long> =
        synchronized(gate) {
            epoch++
            val interrupted = ledger.pendingIds
            val played = ledger.interrupt(head())
            interrupted.forEach { notifications.trySend(PcmVoiceMedia.Playback.Finished(it, false)) }
            track?.let {
                it.pause()
                it.flush()
                it.stop()
                if (!closed) it.play()
            }
            played
        }

    override fun setMicrophoneMuted(muted: Boolean) {
        mutableControls.update { it.copy(microphoneMuted = muted) }
    }

    override fun setPlaybackMuted(muted: Boolean) =
        synchronized(gate) {
            mutableControls.update { it.copy(playbackMuted = muted) }
            if (muted) ledger.mute()
            track?.setVolume(if (muted) 0f else 1f)
            Unit
        }

    private fun fail(error: Exception) =
        synchronized(gate) {
            if (closed) return@synchronized
            val failure = (error as? MediaException)?.failure ?: MediaFailure.Rejected(error.message ?: "Grok audio failed.")
            mutableState.value = RealtimeMediaState.Failed(failure)
            notifications.trySend(PcmVoiceMedia.Playback.Failed(failure.message))
            close()
        }

    override fun close(): Unit =
        synchronized(gate) {
            if (closed) return
            closed = true
            epoch++
            scope.cancel()
            queue.close()
            ledger.pendingIds.forEach { notifications.trySend(PcmVoiceMedia.Playback.Finished(it, false)) }
            ledger.interrupt(head())
            var cleanupError: Exception? = null

            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (error: Exception) {
                    if (cleanupError == null) cleanupError = error
                }
            }
            record?.let { recorder ->
                release { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                release { recorder.release() }
            }
            record = null
            release { echo?.release() }
            echo = null
            release { noise?.release() }
            noise = null
            track?.let { output ->
                release {
                    if (output.state == AudioTrack.STATE_INITIALIZED) {
                        output.pause()
                        output.flush()
                    }
                }
                release { output.release() }
            }
            track = null
            release { route.release() }
            cleanupError?.let {
                val failure = MediaFailure.Rejected("Could not fully release Grok audio: ${it.message ?: it.javaClass.simpleName}")
                mutableState.value = RealtimeMediaState.Failed(failure)
                notifications.trySend(PcmVoiceMedia.Playback.Failed(failure.message))
            }
            mutableControls.update { it.copy(focus = AudioFocusState.NONE) }
            if (mutableState.value !is RealtimeMediaState.Failed) mutableState.value = RealtimeMediaState.Closed
            if (started) cues.play(VoiceCue.Ended)
            notifications.close()
            Unit
        }

    private companion object {
        const val RATE = 24000
    }
}

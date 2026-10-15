package com.colonelpanic.eva.adapters.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A calling SDK's audio device as EVA drives it, 10 ms at a time at [EngineCallLeg.SAMPLE_RATE]
 * PCM16 mono: EVA pulls the other party and pushes its uplink, on its own clock.
 *
 * 10 ms is WebRTC's audio frame, which SDKs built on it (Twilio's is) exchange with a custom audio
 * device. The chunks are direct buffers because the SDK reads and writes them from native code.
 * "Not rendering"/"not capturing" is normal before the SDK has set up media and after it tears it
 * down; callers treat it as silence rather than an error.
 */
internal interface SdkAudioPort {
    /** Fills [chunk] with 10 ms of the other party, or returns false while the SDK is not rendering. */
    fun pull(chunk: ByteBuffer): Boolean

    /** Hands the SDK 10 ms of uplink, or returns false while it is not capturing. */
    fun push(chunk: ByteBuffer): Boolean
}

/** The phone's microphone while the owner holds the floor of an in-app call. */
internal interface OwnerMicrophone {
    /** Fills [samples] with the next stretch of capture, blocking until it is recorded. */
    fun read(samples: ShortArray)

    fun release()
}

/**
 * Call media handed to EVA by an SDK in its own process. Nothing redirects platform audio: the owner
 * hears the other party only through EVA's local playback, and nothing EVA plays reaches capture.
 * The uplink is the agent's paced audio; with the owner on the floor the phone's microphone is
 * mixed in, which is how their speech reaches the other party. [nanoTime] and [sleep] pace reading
 * the other party at real time, since an SDK hands over whatever it has whenever it is asked.
 *
 * Clocks. A real audio device is driven by hardware: a speaker consumes 10 ms every 10 ms, a
 * microphone produces 10 ms every 10 ms, and the SDK's jitter buffer and encoder are built around
 * that. EVA has no hardware here, so it supplies both clocks itself: [readRemote] sleeps until the
 * next frame is due on the monotonic clock (catching up without sleeping if the reader fell behind,
 * so delays never accumulate), and the uplink is driven by the leg's [com.colonelpanic.eva.call.PacedUplink],
 * whose 20 ms frames are pushed as two 10 ms chunks. Pulling faster would drain the SDK's jitter buffer
 * into choppy audio; pulling slower would add latency the agent hears as a slow line.
 *
 * Floor routing. Owner on the floor opens [OwnerMicrophone] and mixes it with whatever agent audio is
 * still queued (normally none: the coordinator cancels agent output on takeover). Mixing rather than
 * replacing mirrors a cellular call, where the injection and the microphone are mixed by the
 * platform. With the agent on the floor the microphone is closed, so the other party never hears the
 * room by accident.
 */
internal class InAppAudioPath(
    private val port: SdkAudioPort,
    private val microphone: () -> OwnerMicrophone,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : CallAudioPath {
    override val ownerHearsRemote = false
    override val capturesLocalPlayback = false

    private val chunkBytes = EngineCallLeg.SAMPLE_RATE / CHUNKS_PER_SECOND * 2
    private val render = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    private val capture = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    private var nextRead = 0L

    @Volatile private var closed = false

    @Volatile private var owner: OwnerMicrophone? = null
    private var spoken = ShortArray(0)

    override fun open() {
        nextRead = nanoTime()
    }

    override fun readRemote(frame: ByteArray): Boolean {
        nextRead += frame.size / 2 * NANOS_PER_SECOND / EngineCallLeg.SAMPLE_RATE
        val wait = (nextRead - nanoTime()) / 1_000_000
        if (wait > 0) sleep(wait)
        if (closed) return false
        for (offset in frame.indices step chunkBytes) {
            render.clear()
            if (port.pull(render)) {
                render.rewind()
                render.get(frame, offset, chunkBytes)
            } else {
                frame.fill(0, offset, offset + chunkBytes)
            }
        }
        return !closed
    }

    override fun writeUplink(frame: ByteArray): Boolean {
        if (closed) return false
        synchronized(this) { owner?.let { mixIn(it, frame) } }
        for (offset in frame.indices step chunkBytes) {
            capture.clear()
            capture.put(frame, offset, chunkBytes)
            capture.rewind()
            port.push(capture)
        }
        return true
    }

    // Synchronized with mixing in writeUplink, so the uplink thread never reads a microphone being released.
    @Synchronized
    override fun routeOwnerMicrophone(toRemote: Boolean) {
        if (closed) return
        val current = owner
        if (toRemote && current == null) owner = microphone()
        if (!toRemote && current != null) {
            owner = null
            current.release()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        owner?.release()
        owner = null
    }

    /** Adds the owner's speech to the agent's frame in place, clamping instead of letting loud audio wrap around. */
    private fun mixIn(
        microphone: OwnerMicrophone,
        frame: ByteArray,
    ) {
        if (spoken.size != frame.size / 2) spoken = ShortArray(frame.size / 2)
        microphone.read(spoken)
        spoken.forEachIndexed { i, sample ->
            val agent = (frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)
            val mixed = (agent + sample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            frame[2 * i] = mixed.toByte()
            frame[2 * i + 1] = (mixed shr 8).toByte()
        }
    }

    private companion object {
        const val CHUNKS_PER_SECOND = 100
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * Voice-communication capture, so the platform removes the other party's playback from what the owner says.
 * Opening it can fail (no permission, the microphone held elsewhere); that surfaces as a failed floor
 * change for the owner rather than a silently dead microphone. Concurrent capture alongside EVA's own
 * voice session is up to the platform's concurrent-capture policy and is not device-verified.
 */
internal class AndroidOwnerMicrophone(
    sampleRateHz: Int,
) : OwnerMicrophone {
    @SuppressLint("MissingPermission")
    private val record =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(
                AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                sampleRateHz / 5 * 2,
            ),
        ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened for the call." } }
            .also(AudioRecord::startRecording)

    override fun read(samples: ShortArray) {
        var filled = 0
        while (filled < samples.size) {
            val read = record.read(samples, filled, samples.size - filled)
            if (read <= 0) {
                samples.fill(0, filled)
                return
            }
            filled += read
        }
    }

    override fun release() {
        runCatching { record.stop() }
        record.release()
    }
}

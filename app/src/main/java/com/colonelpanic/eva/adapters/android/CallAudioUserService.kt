package com.colonelpanic.eva.adapters.android

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku user service as shell, which holds CALL_AUDIO_INTERCEPTION. Exposes only
 * the in-progress call's downlink and uplink, its state, mute and hang-up, EVA's own call-control
 * app op, and starting a call app's view activity from the background.
 *
 * Everything here exists because an ordinary app cannot do it: the call-audio extraction and
 * injection APIs are hidden (called by reflection) and require that permission, the global mic
 * mute and KEYCODE_ENDCALL need shell, only shell can change EVA's MANAGE_ONGOING_CALLS app op, and
 * a background app may not start another app's activity (the WhatsApp transport's call intent).
 * The surface is deliberately narrow because anything here runs with shell's privileges on behalf of
 * EVA. Cellular audio is device-verified on a Pixel 11 Pro Fold (Android 17); other phones may report
 * the PSTN call not interceptable, which [interceptable] surfaces before any call is placed.
 */
@RequiresApi(33)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant")
class CallAudioUserService : ICallAudio.Stub() {
    private val audio: AudioManager by lazy { ShellAudio.audioManager() }
    private var bridge: Bridge? = null

    override fun destroy() {
        bridge?.stop()
        exitProcess(0)
    }

    override fun interceptable(): Boolean = invoke(audio, "isPstnCallAudioInterceptable") as Boolean

    /**
     * The audio mode plus each call's precise state and disconnect causes, parsed from
     * `dumpsys telephony.registry` because shell has no PhoneStateListener for EVA to use. The format
     * is not an API; if it changes, [PRECISE] stops matching and the provider falls back to treating
     * MODE_IN_CALL as a provider-asserted answer and every end as an unknown cause.
     */
    override fun callState(): String {
        val dump =
            ProcessBuilder("dumpsys", "telephony.registry")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        val calls =
            PRECISE.findAll(dump).map { match ->
                buildJsonObject {
                    put("foreground", match.groupValues[1].toInt())
                    put("disconnectCause", match.groupValues[2].toInt())
                    put("preciseDisconnectCause", match.groupValues[3].toInt())
                }
            }
        return buildJsonObject {
            put("mode", audio.mode)
            put("calls", JsonArray(calls.toList()))
        }.toString()
    }

    /**
     * Opens the downlink extraction (TELEPHONY_RX) and uplink injection (USAGE_CALL_ASSISTANT)
     * streams for the live call and returns EVA's end of a socket carrying both: reads are the other
     * party, writes go to them. The downlink is captured in the audio HAL, so it also contains any
     * local playback during the call (see CallMonitorPlayback); the uplink mixes with the phone's
     * microphone unless it is muted. Only one bridge exists at a time.
     */
    @Synchronized
    override fun openAudio(sampleRateHz: Int): ParcelFileDescriptor {
        require(Process.myUid() == SHELL_UID) { "Call audio must run as shell" }
        val mode = audio.mode
        check(mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
            "No cellular or VoIP call is in progress (audio mode $mode)."
        }
        bridge?.stop()
        val (eva, helper) = ParcelFileDescriptor.createSocketPair()
        val format = { mask: Int ->
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(mask)
                .build()
        }
        val record = invoke(audio, "getCallDownlinkExtractionAudioRecord", format(AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
        val injection = { invoke(audio, "getCallUplinkInjectionAudioTrack", format(AudioFormat.CHANNEL_OUT_MONO)) as AudioTrack }
        bridge = Bridge(audio, mode, helper, record, injection, sampleRateHz).also { it.start() }
        return eva
    }

    override fun setUplinkInjection(enabled: Boolean) {
        checkNotNull(bridge) { "No call audio is open." }.inject(enabled)
    }

    override fun startView(
        uri: String,
        mimeType: String,
        packageName: String,
    ): String? {
        val process =
            ProcessBuilder(
                "am",
                "start",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                uri,
                "-t",
                mimeType,
                "-p",
                packageName,
                "-f",
                "0x10000000",
            ).redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0 && "Error" !in output) null else output.trim().ifEmpty { "am exited with ${process.exitValue()}" }
    }

    /** Ends the foreground call the way a hardware end-call key would; used when Telecom control is unavailable. */
    override fun endCall() {
        ProcessBuilder("input", "keyevent", "KEYCODE_ENDCALL").start().waitFor()
    }

    /** Android's global microphone mute: it silences the owner's mic to the other party without touching the injection. */
    override fun setMicrophoneMuted(muted: Boolean) {
        audio.isMicrophoneMute = muted
    }

    /**
     * Lets Telecom bind EVA's non-UI InCallService (CallControlService) for calls placed from now on,
     * which is what gives EVA real keypad DTMF and a clean hang-up. Returns null or appops' error.
     */
    override fun allowCallControl(packageName: String): String? {
        val process =
            ProcessBuilder("appops", "set", packageName, "MANAGE_ONGOING_CALLS", "allow")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) null else output.trim().ifEmpty { "appops exited with ${process.exitValue()}" }
    }

    /**
     * The downlink thread owns and releases the record. The uplink track can be released and
     * recreated while the call goes on (VoIP floor changes), so it is swapped only under the lock.
     */
    private class Bridge(
        private val audio: AudioManager,
        private val mode: Int,
        private val socket: ParcelFileDescriptor,
        private val record: AudioRecord,
        private val injection: () -> AudioTrack,
        sampleRateHz: Int,
    ) {
        @Volatile private var running = true
        private val frameBytes = sampleRateHz / 50 * 2
        private val active = AtomicInteger(2)
        private val lock = Any()
        private var track: AudioTrack? = injection()

        fun start() {
            record.startRecording()
            track?.play()
            Thread({
                val buffer = ByteArray(frameBytes)
                try {
                    val out = FileOutputStream(socket.fileDescriptor)
                    while (running && audio.mode == mode) {
                        // Stops by itself when the call ends (the mode leaves the call's mode).
                        val read = record.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                } catch (_: IOException) {
                } catch (_: IllegalStateException) {
                }
                runCatching { record.stop() }
                record.release()
                finished()
            }, "eva-call-downlink").start()
            Thread({
                val buffer = ByteArray(frameBytes)
                try {
                    val input = FileInputStream(socket.fileDescriptor)
                    while (running) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        // Only whole 16-bit samples; a split sample would shift every later one by a byte.
                        synchronized(lock) { track?.write(buffer, 0, read and 1.inv()) }
                    }
                } catch (_: IOException) {
                } catch (_: IllegalStateException) {
                }
                synchronized(lock) { release() }
                finished()
            }, "eva-call-uplink").start()
        }

        fun inject(enabled: Boolean) =
            synchronized(lock) {
                check(running) { "Call audio has stopped." }
                if (!enabled) {
                    release()
                } else if (track == null) {
                    track = injection().also { it.play() }
                }
            }

        private fun release() {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
        }

        /** Ends both directions; each thread releases its own object as it exits. */
        fun stop() {
            running = false
            runCatching { socket.close() }
        }

        private fun finished() {
            running = false
            if (active.decrementAndGet() == 0) runCatching { socket.close() }
        }
    }

    /**
     * AudioRecord and AudioTrack attribute themselves to the process's application, which must be
     * shell's for the CALL_AUDIO_INTERCEPTION check to pass. A Shizuku user service has no Android
     * application of its own, so one is assembled by reflection around the system context with
     * shell's package name and UID.
     */
    private object ShellAudio {
        fun audioManager(): AudioManager {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread =
                threadClass.getMethod("currentActivityThread").invoke(null)
                    ?: threadClass.getDeclaredConstructor().let {
                        it.isAccessible = true
                        it.newInstance().also { created ->
                            field(threadClass, "sCurrentActivityThread").set(null, created)
                            field(threadClass, "mSystemThread").setBoolean(created, true)
                        }
                    }
            val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
            val shell = ShellContext(system)
            field(threadClass, "mInitialApplication").set(thread, Instrumentation.newApplication(Application::class.java, shell))
            return AudioManager::class.java
                .getDeclaredConstructor(Context::class.java)
                .also { it.isAccessible = true }
                .newInstance(shell)
        }

        private fun field(
            owner: Class<*>,
            name: String,
        ) = owner.getDeclaredField(name).also { it.isAccessible = true }
    }

    private class ShellContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE

        override fun getOpPackageName() = SHELL_PACKAGE

        override fun getAttributionSource(): AttributionSource =
            AttributionSource
                .Builder(SHELL_UID)
                .setPackageName(SHELL_PACKAGE)
                .build()

        override fun getDeviceId() = 0

        override fun getApplicationContext() = this
    }

    private companion object {
        const val SHELL_UID = 2000
        const val SHELL_PACKAGE = "com.android.shell"
        val PRECISE =
            Regex(
                """Foreground call state: (-?\d+), Background call state: -?\d+, Disconnect cause: (-?\d+), Precise disconnect cause: (-?\d+)""",
            )

        fun invoke(
            target: Any,
            name: String,
            vararg args: Any,
        ): Any? {
            val method = target.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
            return try {
                method.invoke(target, *args)
            } catch (error: InvocationTargetException) {
                throw error.cause ?: error
            }
        }
    }
}

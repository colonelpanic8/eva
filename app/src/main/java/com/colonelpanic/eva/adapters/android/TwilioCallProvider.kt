package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallMediaKind
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.CallProvider
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import com.colonelpanic.eva.call.TwilioAccessToken
import com.colonelpanic.eva.call.TwilioApiKey
import com.colonelpanic.eva.data.configuration.TwilioCallsConfiguration
import com.twilio.voice.AudioDevice
import com.twilio.voice.AudioDeviceContext
import com.twilio.voice.AudioFormat
import com.twilio.voice.AudioOptions
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.LogLevel
import com.twilio.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 * Places a VoIP call from EVA to the phone network through the Twilio Voice SDK, entirely in EVA's
 * process: no Shizuku, no telephony audio interception. The other party sees the configured Twilio
 * caller ID. Everything but connecting, following the SDK's call and its keypad is [CallEngine]'s.
 */
class TwilioCallProvider internal constructor(
    context: Context,
    transport: TwilioTransport,
    isEmergency: (String) -> Boolean,
) : CallProvider by CallEngine(context, null, transport, isEmergency) {
    constructor(
        context: Context,
        account: TwilioCallAccount,
        isEmergency: (String) -> Boolean = { PhoneCallBackend.emergency(context, it) },
    ) : this(
        context,
        TwilioTransport(account, AndroidTwilioVoiceSdk(context.applicationContext), PlatformPhoneNumberKey(context)) {
            microphoneGranted(context)
        },
        isEmergency,
    )

    private companion object {
        fun microphoneGranted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

/** The owner's Twilio setup: portable identifiers and this phone's API key. */
interface TwilioCallAccount {
    fun configuration(): TwilioCallsConfiguration?

    fun apiKey(): TwilioApiKey?
}

/**
 * What EVA uses of the Twilio Voice SDK, so the transport is testable without it. The real SDK is a
 * native library that cannot run in a JVM test; everything with decisions in it (readiness, token,
 * destination, event mapping, keypad gating) sits in front of this seam and is tested with a fake.
 */
internal interface TwilioVoiceSdk {
    /** The SDK's audio device, which hands EVA every call's media. */
    val audio: SdkAudioPort

    /** Connects through the TwiML application the token grants; [events] arrive on the main thread. Call on the main thread. */
    fun connect(
        accessToken: String,
        params: Map<String, String>,
        events: TwilioCallEvents,
    ): TwilioCallHandle
}

internal interface TwilioCallHandle {
    fun sendDigits(digits: String)

    fun disconnect()
}

/** A Twilio Voice SDK error: its code, message and explanation, as the SDK reports them. */
internal data class TwilioError(
    val code: Int,
    val message: String,
    val explanation: String?,
) {
    override fun toString() =
        "Twilio error $code: $message" + (explanation?.takeIf { it.isNotBlank() && it != message }?.let { " ($it)" } ?: "")
}

/** The SDK's `Call.Listener`, as plain events. */
internal interface TwilioCallEvents {
    fun ringing()

    fun connected()

    fun reconnecting(error: TwilioError)

    fun reconnected()

    fun connectFailed(error: TwilioError)

    fun disconnected(error: TwilioError?)
}

/**
 * Calls the destination through the owner's TwiML application, whose voice URL must dial the `To`
 * parameter from the configured caller ID with `answerOnBridge`, so the SDK's call connects only
 * when the other party answers (EVA's own function in `experiments/call-audio/twilio` does). The
 * access token is minted on the phone from the owner's API key. Keys are real DTMF signalling.
 *
 * How a call flows: the SDK opens a VoIP leg from EVA (identity `client:eva`) to Twilio; Twilio
 * fetches the TwiML app's voice URL with `To`; that TwiML dials the phone network from the caller
 * ID and bridges the two legs. So the SDK's call states are the client leg's, and they mean what
 * the TwiML makes them mean. With `answerOnBridge` the client leg rings until the dialed party
 * answers, which is what makes "connected" honest answer evidence. A TwiML app without it would
 * report connected as soon as Twilio picked up, before anyone answered; that is a configuration
 * error this transport cannot detect.
 *
 * Why the destination goes in a parameter and the caller ID lives in the TwiML: the SDK can only
 * pass parameters; what Twilio dials and from which number is decided server-side, so the caller
 * ID in EVA's configuration is informative (shown to the owner and the agent as `callerId`) and
 * must match what the TwiML uses.
 *
 * Why tokens are minted on the phone: the alternative is a token server, a second service the owner
 * would run and keep reachable. An API key in EVA's encrypted secret store is equivalent to what such
 * a server would hold, and the token it signs grants only outgoing calls through one TwiML app.
 */
internal class TwilioTransport(
    private val account: TwilioCallAccount,
    private val sdk: TwilioVoiceSdk,
    private val numbers: PhoneNumberKey,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val main: CoroutineContext = Dispatchers.Main,
    private val microphoneGranted: () -> Boolean,
) : InAppAudioTransport {
    override val id = ID
    override val traits
        get() =
            CallProviderTraits(
                mediaKind = CallMediaKind.LOCAL_PCM,
                supportsDtmf = true,
                usesOwnerCallerId = false,
                survivesProcessDeath = false,
                // Nothing needs an Activity: the SDK, its audio device and EVA's playback all run in EVA's
                // process. The owner's microphone on takeover needs EVA's foreground service, as any
                // background capture does. Claimed from design; not yet verified on a locked phone.
                worksLocked = true,
                callerId = account.configuration()?.callerId,
            )
    override val lostCheck = LOST_CHECK

    // The SDK has one audio device for the whole process, so one Twilio call at a time.
    @Volatile private var active: TwilioCall? = null

    override fun busy() = active?.ended == false

    override suspend fun unavailableReason(): String? =
        when {
            account.configuration() == null -> {
                "Twilio calling is not set up: enter the account SID, TwiML app SID, caller ID and API key under Settings > " +
                    "Phone call agent > Twilio calls."
            }

            account.apiKey() == null -> {
                "This phone has no Twilio API key for calls; enter it under Settings > Phone call agent > Twilio calls."
            }

            !microphoneGranted() -> {
                "Grant EVA the Microphone permission: the Twilio Voice SDK will not connect a call without it, even though EVA " +
                    "supplies the call's audio itself."
            }

            else -> {
                null
            }
        }

    override suspend fun dial(number: String): InAppCall {
        unavailableReason()?.let { throw CallNotPlaced(it) }
        // The TwiML dials only E.164; a number written for a person ("(559) 249-3930") is read in the
        // phone's country. What cannot become E.164 (short codes, extensions) is refused before dialing.
        val destination =
            numbers.of(number).takeIf(PhoneNumberKey.E164::matches)
                ?: throw CallNotPlaced("The Twilio route calls only full phone numbers with a country code; $number is not one.")
        val configuration = checkNotNull(account.configuration())
        val token =
            TwilioAccessToken.mint(
                configuration.accountSid,
                checkNotNull(account.apiKey()),
                configuration.twimlAppSid,
                IDENTITY,
                nowSeconds(),
            )
        val call = TwilioCall(sdk.audio, main)
        val handle =
            withContext(main) {
                try {
                    sdk.connect(token, mapOf("To" to destination), call)
                } catch (error: SecurityException) {
                    throw CallNotPlaced("The Twilio Voice SDK refused to start the call: ${error.message}.")
                }
            }
        call.attach(handle)
        active = call
        // Waiting for the first event lets a bad key, token or app SID end as "no call was placed" instead
        // of a leg that ends a moment later. It is not a timeout on the call: if nothing arrives, the leg is
        // returned anyway and follows the call as usual.
        call.refusal(FIRST_SIGNAL_MILLIS)?.let { throw CallNotPlaced("Twilio refused the call before dialing anyone: $it.") }
        return call
    }

    companion object {
        const val ID = "twilio"

        // The token's client identity; dial.js answers only `client:eva`, so another app on the same
        // TwiML app (or a leaked token for another identity) cannot dial through it.
        const val IDENTITY = "eva"
        const val FIRST_SIGNAL_MILLIS = 15_000L
        const val LOST_CHECK = "The Twilio call may still be connected; check the call log in the Twilio console."
    }
}

/**
 * One Twilio call, followed through the SDK's events. Events arrive on the main thread and only
 * record the latest [LegObservation]; the shared leg polls it like any other route. The first end
 * is final: the SDK can report a failure and then a disconnect for the same call.
 */
internal class TwilioCall(
    override val audio: SdkAudioPort,
    private val main: CoroutineContext = Dispatchers.Main,
) : InAppCall,
    TwilioCallEvents {
    private val observation = MutableStateFlow<LegObservation>(LegObservation.Pending)

    @Volatile private var handle: TwilioCallHandle? = null

    @Volatile private var rang = false

    @Volatile private var answered = false

    @Volatile private var failure: TwilioError? = null

    override val observer = "the Twilio Voice SDK"
    override val lostCheck = TwilioTransport.LOST_CHECK
    val ended get() = observation.value is LegObservation.Gone

    fun attach(handle: TwilioCallHandle) {
        this.handle = handle
    }

    /**
     * Waits up to [millis] for the call's first event. Returns the error when Twilio refused the call
     * before it rang for a reason that means nothing was dialed (the access token or application).
     */
    suspend fun refusal(millis: Long): TwilioError? {
        withTimeoutOrNull(millis) { observation.first { it != LegObservation.Pending } }
        return failure?.takeIf { !rang && notDialed(it.code) }
    }

    override suspend fun observe() = observation.value

    /**
     * `sendDigits` makes Twilio send RFC 2833 events, which IVRs (Twilio's Gather included) recognize,
     * unlike in-band tones; so [inBand] is never used. Keys before answer are refused rather than
     * queued: with answerOnBridge they would go to Twilio's ringing leg, not the other party. The SDK
     * returns at once, so the delay keeps one key per call paced like a keypad press, letting the
     * coordinator stop precisely between keys.
     */
    override suspend fun sendDtmf(
        digits: String,
        inBand: suspend (String) -> Unit,
    ): String {
        check(answered && !ended) { "The Twilio call is not connected." }
        for (key in digits) {
            withContext(main) { checkNotNull(handle).sendDigits(key.toString()) }
            delay(KEY_MILLIS)
        }
        return "Sent as keypad signalling (RFC 2833) through the Twilio Voice SDK."
    }

    override suspend fun hangUp() {
        withContext(main) { handle?.disconnect() }
    }

    override fun ringing() = update(LegObservation.Live(null)).also { rang = true }

    override fun connected() {
        rang = true
        answered = true
        update(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION))
    }

    // The SDK is recovering from a network change; the call is still live and media resumes on its own.
    override fun reconnecting(error: TwilioError) = Unit

    override fun reconnected() = Unit

    override fun connectFailed(error: TwilioError) = end(error)

    override fun disconnected(error: TwilioError?) = end(error)

    private fun end(error: TwilioError?) {
        failure = error
        update(
            if (error == null) {
                LegObservation.Gone(EndCause.REMOTE_HANGUP, "Twilio reported that the call ended.")
            } else {
                LegObservation.Gone(endCause(error.code, answered), detail(error))
            },
        )
    }

    private fun detail(error: TwilioError) =
        if (error.code == CallException.EXCEPTION_DECLINE_ERROR && !answered) {
            "$error. Twilio ended the call before anyone answered: the line did not answer, or Twilio could not place " +
                "the dialed call; the dialed call's notifications in the Twilio console say which."
        } else {
            "$error"
        }

    private fun update(next: LegObservation) {
        if (!ended) observation.value = next
    }

    companion object {
        /** Each key's tone and the gap after it, so stopping between keys is precise. */
        private const val KEY_MILLIS = 400L

        /**
         * Access-token, authorization and application errors: Twilio refused the call before running the
         * TwiML that dials. Only these, and only before ringing, are certain enough to report "no call was
         * placed"; anything later may already have reached the phone network.
         */
        fun notDialed(code: Int) = code in 20_101..20_199 || code in 31_201..31_208 || code == 21_218 || code == 31_100

        /**
         * The leg's end cause for an SDK error code, or null when the code does not say (the shared tracker
         * then decides from whether the call was answered). The full code, message and explanation always
         * go into the leg's detail, so the owner sees what Twilio said even where the cause is coarse.
         */
        fun endCause(
            code: Int,
            answered: Boolean,
        ): EndCause? =
            when (code) {
                // EVA's dial.js rejects its call as busy (486) only when the dialed line was busy.
                CallException.EXCEPTION_BUSY_HERE_ERROR, CallException.EXCEPTION_BUSY_EVERYWHERE_ERROR -> {
                    EndCause.BUSY
                }

                // A 603 is how an unanswered SDK call ends whenever the TwiML hangs up, which dial.js does
                // after no answer and after Twilio failed to place the dialed call (for example error 13227,
                // a country not enabled in the account's geo permissions). It does not say which.
                CallException.EXCEPTION_DECLINE_ERROR -> {
                    null
                }

                CallException.EXCEPTION_TEMPORARILY_UNAVAILABLE_ERROR -> {
                    EndCause.NO_ANSWER
                }

                CallException.EXCEPTION_CALL_CANCELLED -> {
                    EndCause.LOCAL_HANGUP
                }

                CallException.EXCEPTION_CONNECTION_ERROR, CallException.EXCEPTION_TRANSPORT_ERROR,
                CallException.EXCEPTION_SIGNALING_CONNECTION_DISCONNECTED, CallException.EXCEPTION_MEDIA_CONNECTION_FAILED,
                CallException.EXCEPTION_MEDIA_DTLS_TRANSPORT_FAILED,
                -> {
                    if (answered) EndCause.DROPPED else EndCause.FAILED
                }

                else -> {
                    if (notDialed(code) || code in DESTINATION_ERRORS) EndCause.FAILED else null
                }
            }

        private val DESTINATION_ERRORS =
            setOf(
                CallException.EXCEPTION_INVALID_PHONE_NUMBER,
                CallException.EXCEPTION_INVALID_CALLER_ID,
                CallException.EXCEPTION_ADDRESS_INCOMPLETE_ERROR,
                CallException.EXCEPTION_DOES_NOT_EXIST_ANYWHERE_ERROR,
            )
    }
}

/** The Twilio Voice SDK with [TwilioAudioDevice] as its only audio device. */
internal class AndroidTwilioVoiceSdk(
    private val context: Context,
) : TwilioVoiceSdk {
    override val audio: TwilioAudioDevice by lazy {
        // At its default level the SDK logs every ICE candidate, the phone's public address included.
        Voice.setLogLevel(LogLevel.ERROR)
        TwilioAudioDevice().also(Voice::setAudioDevice)
    }

    override fun connect(
        accessToken: String,
        params: Map<String, String>,
        events: TwilioCallEvents,
    ): TwilioCallHandle {
        audio
        val options =
            ConnectOptions
                .Builder(accessToken)
                .params(params)
                // The uplink is synthesized speech or tones; the owner's microphone has the platform's own processing.
                .audioOptions(
                    AudioOptions
                        .Builder()
                        .echoCancellation(false)
                        .autoGainControl(false)
                        .noiseSuppression(false)
                        .highpassFilter(false)
                        .build(),
                ).build()
        val call = Voice.connect(context, options, listener(events))
        return object : TwilioCallHandle {
            override fun sendDigits(digits: String) = call.sendDigits(digits)

            override fun disconnect() = call.disconnect()
        }
    }

    private fun listener(events: TwilioCallEvents) =
        object : Call.Listener {
            override fun onConnectFailure(
                call: Call,
                error: CallException,
            ) = events.connectFailed(error.plain())

            override fun onRinging(call: Call) = events.ringing()

            override fun onConnected(call: Call) = events.connected()

            override fun onReconnecting(
                call: Call,
                error: CallException,
            ) = events.reconnecting(error.plain())

            override fun onReconnected(call: Call) = events.reconnected()

            override fun onDisconnected(
                call: Call,
                error: CallException?,
            ) = events.disconnected(error?.plain())
        }

    private fun CallException.plain() = TwilioError(errorCode, message.orEmpty(), explanation)
}

/**
 * The Twilio Voice SDK's audio device: rather than a microphone and speaker, EVA itself. The SDK
 * says when capture and rendering start and stop; EVA's call leg pulls the other party and pushes
 * its uplink through [SdkAudioPort] on its own clock.
 *
 * The SDK takes its audio device once per process (it refuses to change it during a call), which
 * is why there is exactly one instance, created lazily by [AndroidTwilioVoiceSdk] before the first
 * connect. Each context is guarded by its own lock so EVA never calls into native code with a
 * context the SDK has just stopped, while capture and render never wait on each other.
 */
internal class TwilioAudioDevice :
    AudioDevice,
    SdkAudioPort {
    private val format = AudioFormat(EngineCallLeg.SAMPLE_RATE, AudioFormat.AUDIO_SAMPLE_MONO)
    private val captureLock = Any()
    private val renderLock = Any()
    private var capturer: AudioDeviceContext? = null
    private var renderer: AudioDeviceContext? = null

    override fun getCapturerFormat() = format

    override fun onInitCapturer() = true

    override fun onStartCapturing(context: AudioDeviceContext): Boolean {
        synchronized(captureLock) { capturer = context }
        return true
    }

    override fun onStopCapturing(): Boolean {
        synchronized(captureLock) { capturer = null }
        return true
    }

    override fun getRendererFormat() = format

    override fun onInitRenderer() = true

    override fun onStartRendering(context: AudioDeviceContext): Boolean {
        synchronized(renderLock) { renderer = context }
        return true
    }

    override fun onStopRendering(): Boolean {
        synchronized(renderLock) { renderer = null }
        return true
    }

    override fun pull(chunk: ByteBuffer): Boolean =
        synchronized(renderLock) {
            val context = renderer ?: return false
            AudioDevice.audioDeviceReadRenderData(context, chunk)
            true
        }

    override fun push(chunk: ByteBuffer): Boolean =
        synchronized(captureLock) {
            val context = capturer ?: return false
            AudioDevice.audioDeviceWriteCaptureData(context, chunk)
            true
        }
}

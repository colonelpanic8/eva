package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallLeg
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.CallProvider
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import com.colonelpanic.eva.call.PlaceCallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The irreducible parts of one way of calling (cellular, WhatsApp, Twilio, ...). [CallEngine] does
 * everything else: refusing emergency numbers and a second call, the leg's state machine, pacing
 * agent audio, floor routing, listen-in, keypad receipts, hang-up, cleanup and recovery. A transport
 * belongs to one of two media families, which decide where the call's audio meets EVA.
 *
 * Why two families rather than one interface: the call agent always needs the same thing, a
 * [com.colonelpanic.eva.call.CallMedia.LocalPcm] stream of the other party and an uplink it can
 * write, but Android offers two very different ways to get it.
 *
 * - When Android itself carries the call (a cellular call, or another app's VoIP call such as
 *   WhatsApp's), the audio belongs to the platform. EVA can only reach it by asking the platform to
 *   redirect it, which needs privileges a normal app lacks, hence the Shizuku helper running as
 *   shell. Those calls are also visible in Telecom, which is how EVA follows them, presses keys and
 *   hangs up. That is [HelperAudioTransport].
 * - When an SDK inside EVA carries the call (Twilio's Voice SDK), the SDK lets EVA be its audio
 *   device, so the media is EVA's own PCM from the start. There is nothing to redirect, nothing in
 *   Telecom, and no privilege needed; the SDK's own events say how the call is going. That is
 *   [InAppAudioTransport].
 *
 * Everything that is about the call task rather than about where audio comes from lives once, in
 * [EngineCallLeg], so every route behaves the same for the coordinator and the owner: the same leg
 * states, the same pacing, the same floor and listen-in semantics, the same cleanup.
 *
 * The interface is sealed so the engine's `when` over the two families stays exhaustive.
 */
internal sealed interface CallTransport {
    val id: String
    val traits: CallProviderTraits

    /** What the owner should check when EVA loses sight of a call. */
    val lostCheck: String

    /** Transport-specific readiness, such as a permission, an installed app or its configuration. */
    suspend fun unavailableReason(): String?
}

/**
 * A call Android itself carries (Telecom), whose audio EVA reaches by redirecting it through the
 * Shizuku call-audio helper. The engine finds the transport's Telecom call, grants itself call
 * control, presses keys through Telecom and follows the call by polling.
 */
internal interface HelperAudioTransport : CallTransport {
    val redirect: CallRedirect

    /** Whether the transport's calls can be followed only through [CallControlService]. */
    val needsCallControl: Boolean

    /** Receipt wording for EVA turning call control on for itself when it placed a call. */
    val grantNote: String

    /** Starts the call. Throws [CallNotPlaced] when certain nothing was dialed. */
    suspend fun dial(
        number: String,
        helper: ICallAudio,
    )

    /** Whether [call] is one of this transport's calls, and when [number] is given, the one dialing it. */
    @RequiresApi(33)
    fun owns(
        call: Call,
        number: String?,
    ): Boolean

    /** A fresh reading of one call's signals; it may remember earlier readings. */
    fun observer(): CallObserver

    /** Why recovery cannot tell whether a live call is this transport's, or null when it can. */
    fun recoveryDoubt(signals: CallSignals): String? = null
}

/**
 * A call carried by an SDK inside EVA's own process, which hands its media to EVA as PCM: no
 * Telecom call, no Shizuku and no redirection. The call ends with EVA's process.
 *
 * "Ends with EVA's process" is a property of the family, not a gap in one transport: the SDK's
 * signalling connection and media session are objects in EVA's memory. If the process dies, the
 * provider's servers see the client vanish and tear the call down; a restarted EVA has no way to
 * pick the session back up. Such transports therefore report `survivesProcessDeath = false`, and
 * [CallEngine.reattach] never returns a leg for them.
 */
internal interface InAppAudioTransport : CallTransport {
    /**
     * Starts the call; the returned call reports progress, presses keys, hangs up and carries the
     * SDK's audio. Throws [CallNotPlaced] when certain nothing was dialed.
     */
    suspend fun dial(number: String): InAppCall

    /** Reports the SDK handle before a cancelled dial can lose its return value. */
    suspend fun dial(
        number: String,
        onStarted: (InAppCall) -> Unit,
    ): InAppCall

    /** Whether this transport has a call in progress in EVA's process. */
    fun busy(): Boolean
}

/** One call of an [InAppAudioTransport]: its progress, keypad and hang-up, plus the SDK port its audio uses. */
internal interface InAppCall : CallRoute {
    val audio: SdkAudioPort
}

/** Everything EVA can read about calls at one moment, as plain data. */
data class CallSignals(
    val audioMode: Int,
    /** Telephony's precise call states, from the helper; meaningful for cellular calls only. */
    val precise: List<PreciseCall>,
    /** The transport's own call as Telecom shows it to [CallControlService], if visible. */
    val telecom: TelecomCallState?,
    val millisSinceDial: Long,
)

data class PreciseCall(
    val foreground: Int,
    val disconnectCause: Int,
)

data class TelecomCallState(
    val state: Int,
    val disconnectCode: Int,
    val disconnectDetail: String,
    /** Telecom has removed the call from the in-call service. */
    val removed: Boolean,
)

fun interface CallObserver {
    fun observe(signals: CallSignals): LegObservation
}

/**
 * Places calls through one [CallTransport]. A [HelperAudioTransport]'s audio is reached through the
 * Shizuku call-audio helper ([host]); an [InAppAudioTransport]'s comes from its SDK. Either way the
 * leg is an [EngineCallLeg] over a [CallAudioPath].
 */
internal class CallEngine(
    private val context: Context,
    // Null for in-app transports, which never touch Shizuku; required (checked below) for helper ones.
    private val host: CallAudioAccess?,
    private val transport: CallTransport,
    private val isEmergency: (String) -> Boolean = { PhoneCallBackend.emergency(context, it) },
    // Opened only while the owner holds the floor of an in-app call; injectable so JVM tests need no AudioRecord.
    private val microphone: () -> OwnerMicrophone = { AndroidOwnerMicrophone(EngineCallLeg.SAMPLE_RATE) },
    private val admission: CallAdmission = deviceAdmission,
) : CallProvider {
    init {
        require(transport !is HelperAudioTransport || host != null) { "${transport.id} reaches call audio through the Shizuku helper." }
    }

    override val id get() = transport.id
    override val traits get() = transport.traits
    private val name
        get() =
            when {
                transport is HelperAudioTransport && transport.redirect == CallRedirect.PSTN -> "Cellular call audio"
                else -> "${transport.id} call audio"
            }

    override suspend fun unavailableReason(): String? {
        // An in-app route depends on nothing but itself: no Android 13 floor (that comes from the
        // helper's call-audio APIs), no Shizuku. This is what keeps it available on stock Android.
        if (transport is InAppAudioTransport) return transport.unavailableReason()
        if (Build.VERSION.SDK_INT < 33) return "$name needs Android 13 or newer."
        transport.unavailableReason()?.let { return it }
        val host = checkNotNull(host)
        host.unavailableReason()?.let { return "$it $name runs through Shizuku." }
        return try {
            val helper = host.helper()
            if ((transport as HelperAudioTransport).redirect == CallRedirect.PSTN && !helper.interceptable()) {
                "This phone does not expose cellular call audio to Shizuku, so EVA cannot hear or speak in a call."
            } else {
                null
            }
        } catch (error: Exception) {
            "EVA could not reach its Shizuku call-audio helper: ${error.message ?: error::class.simpleName}."
        }
    }

    override suspend fun place(request: PlaceCallRequest): CallLeg {
        if (isEmergency(request.number)) throw CallNotPlaced("EVA does not place emergency calls on the owner's behalf.")
        val lease = admission.acquire()
        val attempt = PlacementAttempt()
        try {
            return when (transport) {
                is HelperAudioTransport -> placeWithHelper(transport, request, lease, attempt)
                is InAppAudioTransport -> placeInApp(transport, request, lease, attempt)
            }
        } catch (error: Throwable) {
            if (!attempt.started || error is CallNotPlaced) {
                // This exception is the transport's explicit guarantee that no remote call was
                // placed (permission rejection or a confirmed pre-dial SDK refusal). Do not leave
                // a permanent reservation after a definite refusal; every uncertain throw retains it.
                lease.release()
            } else {
                // The platform may have dialed before a cancelled dispatcher delivers the result.
                // Keep the reservation until the route confirms Gone, even when no leg was built.
                attempt.route?.let { route ->
                    lease.observationLost(route)
                    withContext(NonCancellable) { runCatching { route.hangUp() } }
                }
            }
            throw error
        }
    }

    private class PlacementAttempt {
        var started = false
        var route: CallRoute? = null
    }

    /**
     * In-app placement. Refused while a cellular call is up (IN_CALL), while Telecom shows any live
     * call EVA can see, or while the transport already has a call: an SDK audio device serves one
     * call at a time, and placing over a live call would talk over the owner. IN_COMMUNICATION is
     * deliberately not a refusal: EVA's own voice session with the owner uses it, and that is
     * exactly when the owner asks EVA to place a call.
     */
    private suspend fun placeInApp(
        transport: InAppAudioTransport,
        request: PlaceCallRequest,
        lease: CallAdmission.Lease,
        attempt: PlacementAttempt,
    ): CallLeg {
        val audioMode = checkNotNull(context.getSystemService(AudioManager::class.java)).mode
        if (audioMode == AudioManager.MODE_IN_CALL || transport.busy() || liveTelecomCalls().isNotEmpty()) {
            throw CallNotPlaced(ANOTHER_CALL)
        }
        transport.unavailableReason()?.let { throw CallNotPlaced(it) }
        val call =
            transport.dial(request.number) {
                attempt.started = true
                attempt.route = it
            }
        // The other party plays locally on the earpiece, like any call: the owner has no other way to hear them.
        return EngineCallLeg(
            request.legId,
            placing = true,
            call,
            InAppAudioPath(call.audio, microphone),
            ::earpiece,
            CallAudioCapture.open(context, request.legId, EngineCallLeg.SAMPLE_RATE),
            lease,
        )
    }

    private suspend fun placeWithHelper(
        transport: HelperAudioTransport,
        request: PlaceCallRequest,
        lease: CallAdmission.Lease,
        attempt: PlacementAttempt,
    ): CallLeg {
        if (Build.VERSION.SDK_INT < 33) throw CallNotPlaced("$name needs Android 13 or newer.")
        val helper = checkNotNull(host).helper()
        if (signals(helper, null, 0).audioMode == AudioManager.MODE_IN_CALL || liveTelecomCalls().isNotEmpty()) {
            throw CallNotPlaced(ANOTHER_CALL)
        }
        val control = CallControlService.grant(context, helper, transport.grantNote)
        if (!control.allowed && transport.needsCallControl) {
            throw CallNotPlaced("EVA cannot follow a ${transport.id} call without call control: ${control.note}.")
        }
        val before = CallControlService.calls.value.toSet()
        val route = EngineRoute(transport, helper, request.number, before, control)
        attempt.route = route
        attempt.started = true
        transport.dial(request.number, helper)
        route.dialFinished = true
        return helperLeg(request.legId, helper, placing = true, route, lease)
    }

    /**
     * An in-app call ends with EVA's process (see [InAppAudioTransport]), so recovery never finds one.
     * Returning null here is truthful rather than optimistic: the coordinator only calls reattach for
     * providers that claim to survive process death, and recovers in-app tasks as interrupted.
     */
    override suspend fun reattach(legId: String): CallLeg? {
        val transport = transport as? HelperAudioTransport ?: return null
        if (Build.VERSION.SDK_INT < 33) return null
        val lease = admission.acquire()
        try {
            val helper = checkNotNull(host).helper()
            val route = EngineRoute(transport, helper, number = null, before = emptySet(), CallControlService.existing(context))
            val signals = route.signals()
            transport.recoveryDoubt(signals)?.let { error(it) }
            if (transport.observer().observe(signals) !is LegObservation.Live) {
                lease.release()
                return null
            }
            return helperLeg(legId, helper, placing = false, route, lease)
        } catch (error: Throwable) {
            lease.release()
            throw error
        }
    }

    @RequiresApi(33)
    private fun helperLeg(
        legId: String,
        helper: ICallAudio,
        placing: Boolean,
        route: EngineRoute,
        lease: CallAdmission.Lease,
    ): CallLeg {
        val redirect = route.transport.redirect
        // Unpinned voice-communication playback would loop back into a VoIP redirection.
        val device: (() -> AudioDeviceInfo?)? = if (redirect == CallRedirect.VOIP) ::earpiece else null
        return EngineCallLeg(
            legId,
            placing,
            route,
            HelperAudioPath(helper, redirect),
            device,
            CallAudioCapture.open(context, legId, EngineCallLeg.SAMPLE_RATE),
            lease,
        )
    }

    /**
     * The call's communication device, else the earpiece, so the other party plays where a call would.
     * For a VoIP redirection this pinning is required, not cosmetic: AOSP routes playback with a
     * preferred device around the redirection's loopback, which would otherwise capture it. For an
     * in-app call there is no loopback; the pin only keeps the call private to the earpiece.
     */
    private fun earpiece(): AudioDeviceInfo? {
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        return (if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice else null)
            ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }

    private fun liveTelecomCalls() =
        if (Build.VERSION.SDK_INT < 33) {
            emptyList()
        } else {
            CallControlService.calls.value.filter { it.details.state != Call.STATE_DISCONNECTED }
        }

    /**
     * One placed or recovered call of a [HelperAudioTransport]: how the engine observes it (the
     * transport's observer over the helper's audio mode, telephony's precise states and Telecom),
     * presses keys and hangs up. Its in-app counterpart is the transport's own [InAppCall].
     */
    @RequiresApi(33)
    private inner class EngineRoute(
        val transport: HelperAudioTransport,
        private val helper: ICallAudio,
        private val number: String?,
        private val before: Set<Call>,
        private val control: CallControlAccess,
    ) : CallRoute {
        private val reading = transport.observer()
        private val dialedNanos = System.nanoTime()

        @Volatile var dialFinished = false

        @Volatile private var hangUpReturned = false

        @Volatile private var call: Call? = null
        override val observer = "EVA's call-audio helper"
        override val lostCheck get() = transport.lostCheck

        override suspend fun observe(): LegObservation = reading.observe(signals())

        override suspend fun confirmEnded(): Boolean {
            val snapshot = signals()
            val telecom = snapshot.telecom
            if (telecom?.state == Call.STATE_DISCONNECTED) return true
            // The cellular observer can return Pending forever if its first poll failed before it
            // saw IN_CALL. An idle raw reading proves end only after dialing and hang-up both returned.
            return transport.redirect == CallRedirect.PSTN && dialFinished && hangUpReturned &&
                snapshot.audioMode != AudioManager.MODE_IN_CALL && snapshot.precise.none { it.foreground > 0 }
        }

        suspend fun signals() = signals(helper, telecomCall(), (System.nanoTime() - dialedNanos) / 1_000_000)

        /**
         * The transport's Telecom call for this leg: the one dialing [number], else its only call
         * that was not already live when EVA dialed. Once found it is kept, so its end stays visible.
         * The control service sees every call on the phone, including the owner's, so an ambiguous
         * choice returns null rather than pressing keys in, or hanging up, the wrong call.
         */
        private fun telecomCall(): Call? {
            call?.let { return it }
            val candidates = CallControlService.calls.value.filter { it.details.state != Call.STATE_DISCONNECTED && it !in before }
            return (
                number?.let { dialed -> candidates.singleOrNull { transport.owns(it, dialed) } }
                    ?: candidates.singleOrNull { transport.owns(it, null) }
            )?.also { call = it }
        }

        override suspend fun sendDtmf(
            digits: String,
            inBand: suspend (String) -> Unit,
        ): String {
            check(transport.traits.supportsDtmf) { "${transport.id} calls have no keypad." }
            val call = telecomCall()?.takeIf { it in CallControlService.calls.value }
            // Without Telecom's view of the call there is no out-of-band DTMF; tones in the uplink are the
            // only remaining way, and the receipt says so because many IVRs (Twilio's Gather included) ignore them.
            if (call == null) {
                inBand(digits)
                val reason = if (control.allowed) "Android did not bind EVA's in-call service for this call" else control.note
                return "Played as in-band keypad tones, not the phone's keypad signalling: $reason. Many phone systems ignore " +
                    "in-band tones, so check that the prompt responded."
            }
            press(call, digits)
            return "Sent with the phone's own keypad signalling" + (control.note?.takeIf { control.allowed }?.let { "; $it." } ?: ".")
        }

        /**
         * Requests the hang-up; only the observed state ending establishes that it happened. Without
         * the control service the helper injects KEYCODE_ENDCALL as shell, which ends the foreground call.
         */
        override suspend fun hangUp() {
            val call = telecomCall()?.takeIf { it in CallControlService.calls.value }
            if (call != null) {
                withContext(Dispatchers.Main) { call.disconnect() }
            } else {
                withContext(Dispatchers.IO) { helper.endCall() }
            }
            hangUpReturned = true
        }
    }

    companion object {
        private val deviceAdmission = CallAdmission()
        private const val ANOTHER_CALL = "Another call is already in progress. End it before EVA places a new one."
        private const val TONE_MILLIS = 250L
        private const val GAP_MILLIS = 150L

        @RequiresApi(33)
        private suspend fun signals(
            helper: ICallAudio,
            call: Call?,
            millisSinceDial: Long,
        ): CallSignals {
            val snapshot = withContext(Dispatchers.IO) { Json.parseToJsonElement(helper.callState()).jsonObject }
            return CallSignals(
                snapshot["mode"]!!.jsonPrimitive.int,
                snapshot["calls"]!!.jsonArray.map {
                    PreciseCall(it.jsonObject["foreground"]!!.jsonPrimitive.int, it.jsonObject["disconnectCause"]!!.jsonPrimitive.int)
                },
                call?.details?.let { details ->
                    TelecomCallState(
                        details.state,
                        details.disconnectCause?.code ?: DisconnectCause.UNKNOWN,
                        details.disconnectCause?.toString().orEmpty(),
                        call !in CallControlService.calls.value,
                    )
                },
                millisSinceDial,
            )
        }

        /**
         * Holds each key like a keypad press; Telephony turns it into the network's out-of-band DTMF
         * (IMS `startDtmf` on the Pixel). 250 ms on and 150 ms off registered every key with Twilio
         * Gather on the device; in-band tones in the uplink did not.
         */
        @RequiresApi(33)
        private suspend fun press(
            call: Call,
            digits: String,
        ) = withContext(Dispatchers.Main) {
            for (key in digits.uppercase()) {
                val state = call.details.state
                check(state == Call.STATE_ACTIVE) { "The call is not active (Telecom state $state)." }
                call.playDtmfTone(key)
                delay(TONE_MILLIS)
                call.stopDtmfTone()
                delay(GAP_MILLIS)
            }
        }

        /** A call followed through Telecom: its connection's own state and disconnect cause. */
        fun telecomObservation(call: TelecomCallState): LegObservation =
            when {
                call.state == Call.STATE_DISCONNECTED -> {
                    LegObservation.Gone(telecomEndCause(call.disconnectCode), "Telecom disconnect: ${call.disconnectDetail}")
                }

                call.removed -> {
                    LegObservation.Gone(null, "The call left Android's call list in Telecom state ${call.state}.")
                }

                call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING -> {
                    LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION)
                }

                else -> {
                    LegObservation.Live(null)
                }
            }

        fun telecomEndCause(code: Int): EndCause? =
            when (code) {
                DisconnectCause.LOCAL -> EndCause.LOCAL_HANGUP
                DisconnectCause.REMOTE -> EndCause.REMOTE_HANGUP
                DisconnectCause.BUSY, DisconnectCause.REJECTED -> EndCause.BUSY
                DisconnectCause.MISSED -> EndCause.NO_ANSWER
                DisconnectCause.ERROR -> EndCause.FAILED
                else -> null
            }
    }
}

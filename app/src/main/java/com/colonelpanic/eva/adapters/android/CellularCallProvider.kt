package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallMediaKind
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.CallProvider
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Places an ordinary cellular call on the owner's line through Telecom and reaches its audio through
 * the Shizuku call-audio helper. Everything but dialing and reading telephony's call state is
 * [CallEngine]'s ([CellularTransport] is the cellular-specific part).
 *
 * Why this shape. Android gives ordinary apps no access to cellular call audio, and an app that
 * replaced the dialer (a UI InCallService) still could not hear or speak in the call. The one route
 * is the CALL_AUDIO_INTERCEPTION permission, which only privileged code holds; on a stock phone that
 * means a helper process running as the shell user through Shizuku ([CallAudioHost], whose service
 * is `CallAudioUserService`). The helper opens a TELEPHONY_RX capture of the downlink and a
 * USAGE_CALL_ASSISTANT injection into the uplink at 24 kHz and passes both over one socket, polls
 * the precise call state, and toggles the global microphone mute. The call itself is placed through
 * Telecom like any outgoing call, so it uses the owner's line and caller ID, shows in the owner's
 * dialer, and belongs to Telecom rather than to EVA's process: a task recovered after EVA restarts
 * finds the call still in progress ([CallEngine.reattach]).
 *
 * Keypad keys and hang-up go through [CallControlService], a non-UI InCallService Telecom binds next
 * to the dialer once EVA holds the MANAGE_ONGOING_CALLS app op (granted through the same helper),
 * because only Telecom's `playDtmfTone` sends the network's out-of-band DTMF that IVRs act on.
 *
 * Device-verified on a Pixel 11 Pro Fold (Android 17), before the engine split and with the same
 * helper and Telecom calls: audio both ways (NIST time line; Twilio hotline recognizing the agent),
 * Telecom DTMF (Twilio Gather `Digits`), hang-up, mute routing, and a call task started on a locked
 * phone. Other phones may not expose call-audio interception at all; [CallEngine.unavailableReason]
 * says so before anything is dialed. The engine-based cellular leg itself has not yet been on a phone.
 */
class CellularCallProvider(
    context: Context,
    host: CallAudioHost,
    isEmergency: (String) -> Boolean = { PhoneCallBackend.emergency(context, it) },
) : CallProvider by CallEngine(context, host, CellularTransport(context), isEmergency) {
    companion object {
        /** Whether two dialed strings name the same line, ignoring formatting and a leading country code. */
        internal fun sameNumber(
            a: String?,
            b: String,
        ): Boolean {
            val left = a?.filter(Char::isDigit) ?: return false
            val right = b.filter(Char::isDigit)
            if (left.isEmpty() || right.isEmpty()) return false
            val significant = minOf(left.length, right.length, 10)
            return left.takeLast(significant) == right.takeLast(significant)
        }
    }
}

internal class CellularTransport(
    private val context: Context,
) : HelperAudioTransport {
    override val id = "cellular"

    // survivesProcessDeath: Telecom owns the call. worksLocked: placeCall needs no Activity (verified
    // locked; the dialer's in-call screen still lights the display over the lock screen).
    override val traits =
        CallProviderTraits(
            mediaKind = CallMediaKind.LOCAL_PCM,
            supportsDtmf = true,
            usesOwnerCallerId = true,
            survivesProcessDeath = true,
            worksLocked = true,
        )
    override val redirect = CallRedirect.PSTN

    /** Without call control, keys fall back to in-band tones and hang-up to the end-call key. */
    override val needsCallControl = false
    override val grantNote =
        "EVA turned on Android's manage-ongoing-calls access for itself through Shizuku when it placed this call; it " +
            "shows no call screen and leaves the owner's dialer in charge"
    override val lostCheck = "The cellular call may still be active; check the phone."

    override suspend fun unavailableReason(): String? =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            "Grant EVA the Phone permission so it can place calls."
        } else {
            null
        }

    override suspend fun dial(
        number: String,
        helper: ICallAudio,
    ) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: throw CallNotPlaced("This device cannot place phone calls.")
        unavailableReason()?.let { throw CallNotPlaced(it) }
        withContext(Dispatchers.Main) {
            try {
                telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            } catch (_: SecurityException) {
                // placeCall either dials or throws; a SecurityException is a definite "not dialed".
                throw CallNotPlaced("Android did not let EVA place the call.")
            }
        }
    }

    @RequiresApi(33)
    override fun owns(
        call: Call,
        number: String?,
    ) = !call.details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) &&
        (number == null || CellularCallProvider.sameNumber(call.details.handle?.schemeSpecificPart, number))

    override fun observer() = preciseStateObserver()

    companion object {
        // PreciseCallState.PRECISE_CALL_STATE_ACTIVE and DisconnectCause values, as the helper parses them from
        // `dumpsys telephony.registry` (shell has no listener API for them).
        private const val PRECISE_ACTIVE = 1
        private const val DISCONNECT_NORMAL = 2
        private const val DISCONNECT_LOCAL = 3
        private const val DISCONNECT_BUSY = 4

        /**
         * A cellular call is live while the audio mode is IN_CALL; precise call state says when it was
         * answered, and telephony's disconnect cause why it ended. Telecom's view is not used.
         *
         * When the helper cannot read precise state (foreground states all negative), being in call is
         * taken as the provider's assertion of an answer, and the evidence says so. A known gap: the
         * precise state is usually reset by the time the mode leaves IN_CALL, so the cause is often -1,
         * and an answered call the other party ended is then not classified here (the leg reports it as
         * DROPPED), as the NIST line's 3-minute cut-off and the Twilio hotline's goodbye both were on the
         * device. DROPPED makes pending commitments uncertain, which errs on the safe side. Telecom's
         * DisconnectCause (as [CallEngine.telecomObservation] reads it) would distinguish them.
         */
        fun preciseStateObserver(): CallObserver {
            var sawCall = false
            return CallObserver { signals ->
                val foreground = signals.precise.map { it.foreground }
                when {
                    signals.audioMode == AudioManager.MODE_IN_CALL -> {
                        sawCall = true
                        LegObservation.Live(
                            when {
                                PRECISE_ACTIVE in foreground -> AnsweredEvidence.PRECISE_CALL_STATE
                                foreground.all { it < 0 } -> AnsweredEvidence.PROVIDER_ASSERTION
                                else -> null
                            },
                        )
                    }

                    !sawCall -> {
                        LegObservation.Pending
                    }

                    else -> {
                        val cause = signals.precise.map { it.disconnectCause }.firstOrNull { it >= 0 } ?: -1
                        LegObservation.Gone(
                            when (cause) {
                                DISCONNECT_LOCAL -> EndCause.LOCAL_HANGUP
                                DISCONNECT_BUSY -> EndCause.BUSY
                                DISCONNECT_NORMAL -> EndCause.REMOTE_HANGUP
                                else -> null
                            },
                            "Android disconnect cause $cause",
                        )
                    }
                }
            }
        }
    }
}

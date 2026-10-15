package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.ContactsContract
import android.telecom.Call
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.call.CallMediaKind
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.CallProvider
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Places a WhatsApp voice call from the owner's WhatsApp account to a saved contact WhatsApp has
 * synced. Everything but dialing and recognizing WhatsApp's calls is [CallEngine]'s.
 */
class WhatsAppCallProvider(
    context: Context,
    host: CallAudioHost,
    enabled: () -> Boolean = { false },
    isEmergency: (String) -> Boolean = { PhoneCallBackend.emergency(context, it) },
) : CallProvider by CallEngine(context, host, WhatsAppTransport(context, PlatformPhoneNumberKey(context), enabled), isEmergency)

/**
 * Dials through the voice-call row WhatsApp adds to a synced contact, opened from the Shizuku
 * helper so EVA may start it from the background. WhatsApp registers its calls with Telecom as
 * self-managed calls, which [CallControlService] sees and the engine follows and ends; their audio
 * is a VoIP redirection. WhatsApp has no keypad.
 */
internal class WhatsAppTransport(
    private val context: Context,
    private val numbers: PhoneNumberKey,
    private val enabled: () -> Boolean = { false },
) : HelperAudioTransport {
    override val id = "whatsapp"
    override val traits =
        CallProviderTraits(
            mediaKind = CallMediaKind.LOCAL_PCM,
            supportsDtmf = false,
            usesOwnerCallerId = true,
            survivesProcessDeath = true,
            worksLocked = false,
        )
    override val redirect = CallRedirect.VOIP
    override val needsCallControl = true
    override val grantNote =
        "EVA turned on Android's manage-ongoing-calls access for itself through Shizuku so it can follow and end WhatsApp's call"
    override val lostCheck = "The WhatsApp call may still be active; check WhatsApp on the phone."

    override suspend fun unavailableReason(): String? =
        when {
            !enabled() -> {
                "WhatsApp calling is unverified and disabled. Enable it in Settings > Phone call agent to test it."
            }

            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED -> {
                "Grant EVA the Contacts permission so it can find WhatsApp contacts."
            }

            !hasWhatsAppContacts() -> {
                "WhatsApp is not installed or has not added its contacts to this phone's contacts."
            }

            else -> {
                null
            }
        }

    override suspend fun dial(
        number: String,
        helper: ICallAudio,
    ) {
        unavailableReason()?.let { throw CallNotPlaced(it) }
        val row =
            voiceCallRow(numbers.of(number))
                ?: throw CallNotPlaced(
                    "No saved contact that WhatsApp has synced has the number $number. WhatsApp calls reach only saved " +
                        "contacts who use WhatsApp; save the number as a contact, let WhatsApp refresh its contacts, then try again.",
                )
        val failure =
            withContext(Dispatchers.IO) {
                helper.startView(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row).toString(), VOICE_CALL_MIME, PACKAGE)
            }
        if (failure != null) throw CallNotPlaced("Android did not open WhatsApp's call: $failure")
    }

    /** WhatsApp's self-managed calls carry its own phone account; their address is not relied on. */
    @RequiresApi(33)
    override fun owns(
        call: Call,
        number: String?,
    ) = call.details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) &&
        call.details.accountHandle
            ?.componentName
            ?.packageName == PACKAGE

    override fun observer() = telecomObserver()

    override fun recoveryDoubt(signals: CallSignals): String? =
        if (signals.telecom == null && signals.audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            "A VoIP call is in progress but Telecom has not shown EVA a WhatsApp call, so EVA cannot tell whether it is this one."
        } else {
            null
        }

    private fun hasWhatsAppContacts(): Boolean =
        context.contentResolver
            .query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(PACKAGE),
                null,
            )?.use { it.moveToFirst() } ?: false

    /** WhatsApp's voice-call data row for [e164], identified only by the number in WhatsApp's account ID. */
    private fun voiceCallRow(e164: String): Long? =
        context.contentResolver
            .query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(VOICE_CALL_MIME),
                null,
            )?.use { rows ->
                generateSequence { if (rows.moveToNext()) rows.getLong(0) to rows.getString(1) else null }
                    .firstOrNull { (_, account) -> accountNumber(account) == e164 }
                    ?.first
            }

    companion object {
        const val PACKAGE = "com.whatsapp"
        const val VOICE_CALL_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        const val APPEAR_MILLIS = 45_000L

        /**
         * WhatsApp's call is followed through Telecom. Until it appears the call is still being placed;
         * one that never appears is reported, since WhatsApp may be waiting on a screen EVA cannot see.
         */
        fun telecomObserver() =
            CallObserver { signals ->
                val call = signals.telecom
                when {
                    call != null -> {
                        CallEngine.telecomObservation(call)
                    }

                    signals.millisSinceDial < APPEAR_MILLIS -> {
                        LegObservation.Pending
                    }

                    else -> {
                        LegObservation.Gone(
                            EndCause.FAILED,
                            "WhatsApp's call did not appear in Android's call list within ${APPEAR_MILLIS / 1000} s. WhatsApp may " +
                                "be showing a screen that needs a tap, or its call may be running without EVA; check WhatsApp on the phone.",
                        )
                    }
                }
            }

        /** "15592493930@s.whatsapp.net" is +15592493930; group and other account IDs name no phone number. */
        internal fun accountNumber(account: String?): String? {
            val user = account?.removeSuffix(USER_SERVER)?.takeIf { it != account && it.isNotEmpty() && it.all(Char::isDigit) }
            return user?.let { "+$it" }
        }

        private const val USER_SERVER = "@s.whatsapp.net"
    }
}

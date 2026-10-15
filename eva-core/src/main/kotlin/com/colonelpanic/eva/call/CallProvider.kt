package com.colonelpanic.eva.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class CallMediaKind { LOCAL_PCM, MODEL_HOSTED }

@Serializable
data class CallProviderTraits(
    val mediaKind: CallMediaKind,
    val supportsDtmf: Boolean,
    val usesOwnerCallerId: Boolean,
    val survivesProcessDeath: Boolean,
    val worksLocked: Boolean,
    val maxConnectedDurationMillis: Long? = null,
    /** Whether the keypad route also sends A-D, which many telephony routes drop. */
    val dtmfLetterKeys: Boolean = false,
    /** The number the other party sees, when the provider knows it and it is not the owner's own line. */
    val callerId: String? = null,
)

data class PlaceCallRequest(
    /** Stable idempotency/reattachment identity, durably recorded BEFORE placement. */
    val legId: String,
    val number: String,
)

interface CallProvider {
    val id: String
    val traits: CallProviderTraits

    suspend fun unavailableReason(): String?

    /**
     * Must use request.legId as the leg's stable identity; never place twice for that identity.
     * Throws [CallNotPlaced] when it is certain nothing was dialed.
     */
    suspend fun place(request: PlaceCallRequest): CallLeg

    /** Null asserts no live leg exists for this identity; transport failures must throw. */
    suspend fun reattach(legId: String): CallLeg?
}

@Serializable
sealed interface LegState {
    @Serializable data object Placing : LegState

    @Serializable data object Ringing : LegState

    @Serializable data class Connected(
        val sinceMillis: Long,
        val answeredEvidence: AnsweredEvidence,
    ) : LegState

    @Serializable data class Ended(
        val cause: EndCause,
        val detail: String? = null,
    ) : LegState
}

@Serializable
enum class AnsweredEvidence { PRECISE_CALL_STATE, INFERRED_DOWNLINK, PROVIDER_ASSERTION }

@Serializable
enum class EndCause { LOCAL_HANGUP, REMOTE_HANGUP, BUSY, NO_ANSWER, FAILED, DROPPED }

sealed interface LegEvent {
    data class Notice(
        val detail: String,
    ) : LegEvent
}

interface CallLeg {
    val id: String
    val state: StateFlow<LegState>
    val events: Flow<LegEvent>
    val media: CallMedia

    /**
     * Presses [digits] (the coordinator passes one key at a time) and returns once they and their
     * trailing gap have been sent, so stopping between keys is precise. Returns how they were sent
     * (network signalling or in-band tones) for the receipt, without repeating the digits. Throws if
     * delivery failed or became unknown.
     */
    suspend fun sendDtmf(digits: String): String

    suspend fun hangUp()

    suspend fun setOwnerMicrophone(toRemote: Boolean)

    /** Local route or hosted conference monitor; must not change the remote leg or speaking floor. */
    suspend fun setOwnerMonitoring(enabled: Boolean)
}

sealed interface CallMedia {
    interface LocalPcm : CallMedia {
        val sampleRateHz: Int
        val remoteFrames: Flow<PcmFrame>

        /**
         * Frames arrive in generation bursts at sampleRateHz; the provider queues and paces real-time
         * playback. This must enqueue promptly rather than block the coordinator until playback ends.
         * The brain resamples but never sleeps to pace media. Capture supplies real-time remote frames.
         */
        suspend fun sendAgentFrame(frame: PcmFrame)

        /** Clear queued uplink audio as well as cancelling model generation on owner preemption. */
        suspend fun cancelAgentAudio()

        /**
         * Returns once agent audio queued so far has played or been cleared, so the agent's own hang-up
         * does not cut off the goodbye it spoke just before. Providers without a local queue return at once.
         */
        suspend fun awaitAgentAudioPlayed() {}
    }

    interface ModelHosted : CallMedia {
        val sidebandSessionId: String
    }
}

/** Nothing was dialed; the message says why and reaches the owner and the main agent. */
class CallNotPlaced(
    message: String,
) : Exception(message)

/** PCM16 mono; providers retain no ownership of arrays after delivering a frame. */
class PcmFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
) {
    init {
        require(sampleRateHz in 8_000..48_000)
    }
}

class CallProviderRegistry(
    val providers: List<CallProvider> = emptyList(),
) {
    init {
        require(providers.map { it.id }.distinct().size == providers.size)
    }

    suspend fun available(): CallProvider? = providers.firstOrNull { it.unavailableReason() == null }

    /** The [requested] provider, or the first available one; throws [CallNotPlaced] saying why none can call. */
    suspend fun choose(requested: String?): CallProvider {
        if (requested == null) return available() ?: throw CallNotPlaced(checkNotNull(unavailableReason()))
        val provider =
            providers.find { it.id == requested }
                ?: throw CallNotPlaced(
                    "This phone has no '$requested' call route; it has ${providers.joinToString { it.id }.ifEmpty { "none" }}.",
                )
        provider.unavailableReason()?.let { throw CallNotPlaced("The $requested call route is unavailable: $it") }
        return provider
    }

    suspend fun unavailableReason(): String? =
        if (providers.isEmpty()) {
            "No phone call provider is set up. Configure a cellular or hosted call provider before starting a call task."
        } else if (available() != null) {
            null
        } else {
            providers.map { "${it.id}: ${it.unavailableReason()}" }.joinToString("; ")
        }
}

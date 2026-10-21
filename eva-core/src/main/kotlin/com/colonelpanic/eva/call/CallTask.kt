package com.colonelpanic.eva.call

import kotlinx.serialization.Serializable

@Serializable
data class CallBrief(
    val number: String,
    val goal: String,
    val briefing: String,
    val disclosures: String,
    /** Exact, owner-approved commitment identifiers. Natural-language mandate interpretation is not authority. */
    val mandate: Set<String>,
    /** The keypad credentials this call may enter, by reference or secret handle; never their values. */
    val credentials: List<CallCredential> = emptyList(),
    /**
     * Who the call agent represents, by name ("Ivan Malison"), so it can introduce itself as calling
     * on that person's behalf. EVA keeps no profile of its owner; the main agent fills this from what
     * it knows (memory, the conversation). Null in tasks from before the field and when the main agent
     * does not know the name; the stock instructions then have the agent avoid the word "owner".
     */
    val onBehalfOf: String? = null,
)

/** A keypad secret by saved reference (`bank/pin`) or by the call thread's `secret:` handle; never its value. */
@Serializable
data class CallCredential(
    val reference: String,
    val label: String,
)

@Serializable
enum class CallPhase { IVR, HOLD, HUMAN, TRANSFERRING, VERIFYING, UNKNOWN }

@Serializable
enum class CallFloor { AGENT, OWNER, AGENT_STALLING, MUTED }

@Serializable
enum class BrainState { DETACHED, ATTACHED, REPLACING }

@Serializable
enum class CallSpeaker { REMOTE, AGENT, OWNER_TO_REMOTE, OWNER_TO_AGENT, SYSTEM_IVR, DTMF }

@Serializable
data class CallTranscriptItem(
    val id: String,
    val speaker: CallSpeaker,
    val text: String,
    val createdAtMillis: Long,
    val contentTrust: String =
        if (speaker == CallSpeaker.REMOTE ||
            speaker == CallSpeaker.SYSTEM_IVR
        ) {
            "external_data"
        } else {
            "attributed_claim"
        },
)

@Serializable
sealed interface CallTaskState {
    @Serializable data object Briefing : CallTaskState

    @Serializable data object Dialing : CallTaskState

    @Serializable data object InCall : CallTaskState

    @Serializable data object WrappingUp : CallTaskState

    @Serializable data class Accomplished(
        val evidence: List<String>,
        val claim: String,
    ) : CallTaskState

    @Serializable data class NotAccomplished(
        val reason: String,
    ) : CallTaskState

    @Serializable data class Uncertain(
        val unknown: String,
    ) : CallTaskState

    @Serializable data class Interrupted(
        val reason: String,
        val outcome: Uncertain? = null,
    ) : CallTaskState
}

val CallTaskState.terminal: Boolean
    get() =
        this !is CallTaskState.Briefing && this !is CallTaskState.Dialing && this !is CallTaskState.InCall &&
            this !is CallTaskState.WrappingUp

@Serializable
data class CallTaskSnapshot(
    val id: String,
    val threadId: String,
    val turnId: String,
    val providerId: String,
    val traits: CallProviderTraits,
    val brief: CallBrief,
    val state: CallTaskState = CallTaskState.Briefing,
    val legId: String = id,
    val legState: LegState = LegState.Placing,
    val phase: CallPhase = CallPhase.UNKNOWN,
    val floor: CallFloor = CallFloor.AGENT,
    val brain: BrainState = BrainState.DETACHED,
    val listening: Boolean = false,
    val transcript: List<CallTranscriptItem> = emptyList(),
    val pendingCommitment: String? = null,
    val acceptedCommitments: List<String> = emptyList(),
    val proposedOutcome: CallTaskState? = null,
)

data class CallOwnerQuestion(
    val threadId: String,
    val turnId: String,
    val taskId: String,
    val id: String,
    val question: String,
    val urgent: Boolean,
)

/** The owner's saved keypad credentials. Values must never be persisted, returned to the brain or interpolated into errors. */
interface CallCredentialResolver {
    /** Every saved reference with its label, whether or not this device holds its value. */
    fun configured(): List<CallCredential>

    /** Throws [CallCredentialUnavailable] saying what is missing and where the owner sets it. */
    suspend fun resolve(reference: String): CharArray
}

/** Why a credential cannot be entered. The message reaches the model and owner, so it never holds the value. */
class CallCredentialUnavailable(
    message: String,
) : Exception(message)

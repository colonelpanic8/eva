package com.colonelpanic.eva.capability

enum class ExecutionMode { SYNCHRONOUS, HANDOFF }

enum class InteractionMode { VOICE, TYPED }

/**
 * What a voice call does once an action succeeds. A refused, failed, or uncertain action always
 * leaves the call open so the model can say so.
 */
enum class CallEnding(
    val wire: String,
) {
    NEVER("never"),

    /** EVA hangs up after the model's reply to the action's result. */
    AFTER_REPLY("after_reply"),

    /** EVA hangs up once whatever is being said has played; the result is not read back. */
    IMMEDIATELY("immediately"),
    ;

    companion object {
        private val ACTION_ID = Regex("[A-Za-z0-9._:-]{1,300}")

        fun of(wire: String): CallEnding? = entries.find { it.wire == wire }

        /** Overrides name actions that may not be installed here yet, so only their shape is checked. */
        fun checkOverrides(ids: Collection<String>) {
            require(ids.size <= 256) { "At most 256 actions may choose how a voice call ends." }
            ids.forEach { require(ACTION_ID.matches(it)) { "Invalid action identifier for ending a voice call." } }
        }
    }
}

data class ExecutionSemantics(
    val mode: ExecutionMode,
    val requiresForeground: Boolean,
    val maxWaitMillis: Long? = null,
    /** An intent whose target cannot work behind the lock screen: EVA asks for unlock before opening it. */
    val requiresUnlock: Boolean = false,
    val endsVoiceCall: CallEnding = CallEnding.NEVER,
) {
    init {
        require(maxWaitMillis == null || maxWaitMillis > 0)
    }
}

data class WaitBudget(
    val mode: InteractionMode,
    val modeDefaultMillis: Long,
    val capabilityDefaultMillis: Long?,
    val instanceOverrideMillis: Long?,
) {
    init {
        require(modeDefaultMillis > 0)
        require(capabilityDefaultMillis == null || capabilityDefaultMillis > 0)
        require(instanceOverrideMillis == null || instanceOverrideMillis > 0)
    }

    val selectedLayer: String get() =
        when {
            instanceOverrideMillis != null -> "extension override"
            capabilityDefaultMillis != null -> "capability default"
            else -> "${mode.name.lowercase()} default"
        }
    val requestedMillis: Long get() = instanceOverrideMillis ?: capabilityDefaultMillis ?: modeDefaultMillis
    val effectiveMillis: Long get() = requestedMillis.coerceAtMost(CEILING_MILLIS)

    fun receipt(): String =
        "Wait budget: ${effectiveMillis}ms from $selectedLayer" +
            (if (requestedMillis > CEILING_MILLIS) " (clamped to ${CEILING_MILLIS}ms)" else "") +
            "; extension=${instanceOverrideMillis ?: "unset"}, capability=${capabilityDefaultMillis ?: "unset"}, " +
            "${mode.name.lowercase()}=$modeDefaultMillis ms."

    companion object {
        const val CEILING_MILLIS = 60_000L

        fun defaultMillis(mode: InteractionMode): Long = if (mode == InteractionMode.VOICE) 20_000 else 30_000
    }
}

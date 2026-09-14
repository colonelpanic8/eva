package com.colonelpanic.eva.capability

enum class ExecutionMode { SYNCHRONOUS, HANDOFF }

enum class InteractionMode { VOICE, TYPED }

data class ExecutionSemantics(
    val mode: ExecutionMode,
    val requiresForeground: Boolean,
    val maxWaitMillis: Long? = null,
    val cancellation: String = "none",
    val idempotency: String = "none",
    val reconciliation: String = "none",
) {
    init {
        require(maxWaitMillis == null || maxWaitMillis > 0)
        require(cancellation == "none" && idempotency == "none" && reconciliation == "none")
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

package com.colonelpanic.eva.capability

object CatalogAdmission {
    const val LIMIT = 64

    data class Selection(
        val admitted: List<CapabilityDefinition>,
        val overflow: List<CapabilityDefinition>,
    )

    fun select(
        definitions: List<CapabilityDefinition>,
        controls: Int = 0,
    ): Selection {
        require(controls in 0..LIMIT)
        val ordered = definitions.sortedWith(compareBy<CapabilityDefinition> { it.id.startsWith("extension.") }.thenBy { it.id })
        return Selection(ordered.take(LIMIT - controls), ordered.drop(LIMIT - controls))
    }

    /** End, defer, background status/cancel, plus device revise/stop when available. */
    fun voiceControls(definitions: List<CapabilityDefinition>): Int =
        4 + if (definitions.any { it.id == CapabilityRegistry.DEVICE_TASK }) 2 else 0

    fun overflowReasons(definitions: List<CapabilityDefinition>): Map<String, String> {
        val typed = select(definitions).overflow.map { it.id }.toSet()
        val controls = voiceControls(definitions)
        return select(definitions, controls).overflow.associate { capability ->
            capability.id to
                if (capability.id in typed) {
                    "Unavailable: the 64-tool limit is full. Bundled actions have priority; extension IDs are admitted in sorted order."
                } else {
                    "Unavailable in voice: session controls use $controls of 64 slots. Available in typed conversations."
                }
        }
    }
}

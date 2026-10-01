package com.colonelpanic.eva.capability

object CatalogAdmission {
    /** EVA's shared budget; provider evidence and verification limits are in docs/architecture.md. */
    const val LIMIT = 128

    data class Selection(
        val admitted: List<CapabilityDefinition>,
        val overflow: List<CapabilityDefinition>,
    )

    fun select(
        definitions: List<CapabilityDefinition>,
        controls: Int = 0,
    ): Selection {
        require(controls in 0..LIMIT)
        val (extensions, bundled) = definitions.partition { it.id.startsWith("extension.") }
        val orderedBundled = bundled.sortedBy { it.id }
        val admitted = orderedBundled.take(LIMIT - controls).toMutableList()
        val overflow = orderedBundled.drop(LIMIT - controls).toMutableList()
        val groups =
            extensions
                .groupBy { it.source?.id ?: it.id.substringBeforeLast('.') }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<CapabilityDefinition>>> { group ->
                        !group.value.any { it.isInstalledService() }
                    }.thenBy { it.key },
                )
        for (group in groups) {
            val tools = group.value.sortedBy { it.id }
            if (tools.size <= LIMIT - controls - admitted.size) {
                admitted.addAll(tools)
            } else {
                overflow.addAll(tools)
            }
        }
        return Selection(admitted, overflow)
    }

    private fun CapabilityDefinition.isInstalledService(): Boolean =
        source?.id?.contains('/') == true ||
            (!id.startsWith("extension.package.") && !id.startsWith("extension.media."))

    /** Session controls, plus revising and stopping a device task when one can run. */
    fun voiceControls(definitions: List<CapabilityDefinition>): Int =
        4 + if (definitions.any { it.id == CapabilityRegistry.DEVICE_TASK }) 2 else 0

    fun overflowReasons(definitions: List<CapabilityDefinition>): Map<String, String> {
        val typed = select(definitions).overflow.map { it.id }.toSet()
        val controls = voiceControls(definitions)
        val voice = select(definitions, controls).overflow.map { it.id }.toSet()
        val policy =
            "The $LIMIT-tool budget reserves session controls in voice, then prioritizes bundled actions and installed services. " +
                "Other extension groups follow in stable order; whole groups that do not fit the remaining slots are skipped."
        return (typed + voice).sorted().associateWith { id ->
            when {
                id !in typed -> {
                    "Unavailable in voice: session controls use $controls of $LIMIT slots. Available in typed conversations. $policy"
                }

                id !in voice -> {
                    "Unavailable in typed conversations. Available in voice because skipping larger groups leaves room. $policy"
                }

                else -> {
                    "Unavailable in typed and voice conversations. $policy"
                }
            }
        }
    }
}

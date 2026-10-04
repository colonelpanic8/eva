package com.colonelpanic.eva.capability

import com.colonelpanic.eva.providers.ExcludedTool

object CatalogAdmission {
    /**
     * Highest tool count verified with both subscription Responses and Realtime; not a published
     * provider maximum. Realtime alone accepted 4096, but a 2048-tool voice prompt left the model no
     * room to answer, so the bound stays at the count both providers were shown to use.
     */
    const val LIMIT = 512

    data class Selection(
        val admitted: List<CapabilityDefinition>,
        val overflow: List<CapabilityDefinition>,
        val reservedControls: Int,
    ) {
        fun excluded(): List<ExcludedTool> = overflow.map { ExcludedTool(it.id, it.title) }
    }

    data class Preview(
        val text: Selection,
        val voice: Selection,
    )

    /** Each mode drops its own prompt-hidden actions before selection, so hidden tools take no capacity. */
    fun preview(
        definitions: List<CapabilityDefinition>,
        textHidden: Set<String> = emptySet(),
        voiceHidden: Set<String> = emptySet(),
    ): Preview {
        val text = definitions.filterNot { it.id in textHidden }
        val voice = definitions.filterNot { it.id in voiceHidden }
        return Preview(select(text), select(voice, voiceControls(voice)))
    }

    fun select(
        definitions: List<CapabilityDefinition>,
        controls: Int = 0,
    ): Selection {
        require(controls in 0..LIMIT)
        val (extensions, bundled) = definitions.partition { it.id.startsWith("extension.") }
        val orderedBundled = bundled.sortedBy { it.id }
        val admitted = mutableListOf<CapabilityDefinition>()
        val overflow = mutableListOf<CapabilityDefinition>()

        fun admit(tools: List<CapabilityDefinition>) {
            if (tools.size <= LIMIT - controls - admitted.size) admitted.addAll(tools) else overflow.addAll(tools)
        }
        orderedBundled.forEach { admit(listOf(it)) }
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
            admit(tools)
        }
        return Selection(admitted, overflow, controls)
    }

    private fun CapabilityDefinition.isInstalledService(): Boolean =
        source?.id?.contains('/') == true ||
            (!id.startsWith("extension.package.") && !id.startsWith("extension.media."))

    /** Session controls, plus revising and stopping a device task when one can run. */
    fun voiceControls(definitions: List<CapabilityDefinition>): Int =
        5 + if (definitions.any { it.id == CapabilityRegistry.DEVICE_TASK }) 2 else 0

    fun overflowReasons(definitions: List<CapabilityDefinition>): Map<String, String> = overflowReasons(preview(definitions))

    fun overflowReasons(preview: Preview): Map<String, String> {
        val typed =
            preview.text.overflow
                .map { it.id }
                .toSet()
        val voice =
            preview.voice.overflow
                .map { it.id }
                .toSet()
        val policy =
            "Sessions offer at most $LIMIT tools, the count verified with OpenAI; voice also reserves its session controls. " +
                "Bundled actions and installed services come first; remaining whole groups follow in stable order, " +
                "skipping groups that do not fit."
        return (typed + voice).sorted().associateWith { id ->
            when {
                id !in typed -> "Unavailable in voice. Available in typed conversations. $policy"
                id !in voice -> "Unavailable in typed conversations. Available in voice because skipping larger groups leaves room. $policy"
                else -> "Unavailable in typed and voice conversations. $policy"
            }
        }
    }
}

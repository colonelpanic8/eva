package com.colonelpanic.eva.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CatalogAdmission {
    /** Highest tool count verified with subscription Responses and Realtime; not a published provider maximum. */
    const val LIMIT = 512

    /** Native WebRTC advertises 256 KiB; reserve 32 KiB for instructions, controls and server fields. */
    const val VOICE_TOOL_BYTES = 224 * 1024
    const val VOICE_SESSION_BYTES = 248 * 1024

    data class Selection(
        val admitted: List<CapabilityDefinition>,
        val overflow: List<CapabilityDefinition>,
        val metadataBytes: Int,
        val reservedControls: Int,
    ) {
        fun excludedIds(hidden: Set<String>): List<String> = overflow.filterNot { it.id in hidden }.map { it.id }

        fun without(hidden: Set<String>): Selection =
            copy(
                admitted = admitted.filterNot { it.id in hidden },
                overflow = overflow.filterNot { it.id in hidden },
            )
    }

    data class Preview(
        val text: Selection,
        val voice: Selection,
    )

    fun preview(definitions: List<CapabilityDefinition>): Preview =
        Preview(select(definitions), select(definitions, voiceControls(definitions)))

    fun select(
        definitions: List<CapabilityDefinition>,
        controls: Int = 0,
        voiceBytes: Int = VOICE_TOOL_BYTES,
    ): Selection {
        require(controls in 0..LIMIT)
        val (extensions, bundled) = definitions.partition { it.id.startsWith("extension.") }
        val orderedBundled = bundled.sortedBy { it.id }
        val admitted = mutableListOf<CapabilityDefinition>()
        val overflow = mutableListOf<CapabilityDefinition>()
        var bytes = 2L
        val guidanceSources = mutableSetOf<String>()

        fun admit(tools: List<CapabilityDefinition>) {
            val guidance =
                tools
                    .filter { it.source != null && it.guidance != null }
                    .distinctBy { it.source!!.id }
                    .filterNot { it.source!!.id in guidanceSources }
            val guidanceBytes =
                guidance.sumOf { definition ->
                    JsonObject(
                        mapOf(
                            "source" to JsonPrimitive(definition.source!!.title),
                            "guidance" to JsonPrimitive(definition.guidance),
                        ),
                    ).toString()
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong() + 1
                }
            val addedBytes = tools.sumOf { metadataBytes(it).toLong() + 1 } + guidanceBytes
            if (tools.size <= LIMIT - controls - admitted.size && (controls == 0 || bytes + addedBytes <= voiceBytes)) {
                admitted.addAll(tools)
                bytes += addedBytes
                guidanceSources.addAll(guidance.map { it.source!!.id })
            } else {
                overflow.addAll(tools)
            }
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
        return Selection(admitted, overflow, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), controls)
    }

    private fun metadataBytes(definition: CapabilityDefinition): Int =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "name" to JsonPrimitive("eva_tool_${LIMIT - 1}"),
                "description" to JsonPrimitive(definition.modelDescription()),
                "parameters" to definition.inputSchema,
            ),
        ).toString().toByteArray(Charsets.UTF_8).size

    private fun CapabilityDefinition.isInstalledService(): Boolean =
        source?.id?.contains('/') == true ||
            (!id.startsWith("extension.package.") && !id.startsWith("extension.media."))

    /** Session controls, plus revising and stopping a device task when one can run. */
    fun voiceControls(definitions: List<CapabilityDefinition>): Int =
        4 + if (definitions.any { it.id == CapabilityRegistry.DEVICE_TASK }) 2 else 0

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
            "Voice reserves session controls and a ${VOICE_TOOL_BYTES / 1024} KiB tool-metadata budget " +
                "below the native WebRTC message-size boundary. " +
                "Text uses a $LIMIT-tool verified safety bound. Bundled actions and installed services come first; " +
                "remaining whole groups follow in stable order, skipping groups that do not fit."
        return (typed + voice).sorted().associateWith { id ->
            when {
                id !in typed -> "Unavailable in voice. Available in typed conversations. $policy"
                id !in voice -> "Unavailable in typed conversations. Available in voice because skipping larger groups leaves room. $policy"
                else -> "Unavailable in typed and voice conversations. $policy"
            }
        }
    }
}

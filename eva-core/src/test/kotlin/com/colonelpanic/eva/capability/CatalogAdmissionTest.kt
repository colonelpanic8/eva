package com.colonelpanic.eva.capability

import com.colonelpanic.eva.capability.extensions.extensionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CatalogAdmissionTest {
    private fun definition(
        id: String,
        source: CapabilitySource? = null,
    ) = CapabilityDefinition(id, id, id, extensionSchema, source = source)

    private fun bundled(count: Int) = List(count) { definition("eva.bundled.${it.toString().padStart(3, '0')}") }

    private fun group(
        prefix: String,
        count: Int,
        sourceId: String = prefix,
        title: String = prefix,
    ): List<CapabilityDefinition> =
        List(count) { definition("$prefix.action_${it.toString().padStart(3, '0')}", CapabilitySource(sourceId, title)) }

    private fun service(count: Int = 3) = group("extension.sh.paseo.assembly", count, "sh.paseo.assembly/.EvaService")

    @Test
    fun `realistic install offers every workflow in typed and voice conversations`() {
        val definitions =
            bundled(26) + List(8) { group("extension.package.default-$it", 3, "package:default-$it") }.flatten() +
                group("extension.mova", 9, "mova/.EvaService") + service() +
                List(3) { group("extension.media.player-$it", 3, "media:0:player-$it") }.flatten()
        assertEquals(71, definitions.size)
        for (controls in listOf(0, CatalogAdmission.voiceControls(definitions))) {
            val selected = CatalogAdmission.select(definitions, controls)
            assertEquals(definitions.toSet(), selected.admitted.toSet())
            assertTrue(selected.overflow.isEmpty())
        }
        assertTrue(CatalogAdmission.overflowReasons(definitions).isEmpty())
    }

    @Test
    fun `bundled and whole installed service workflows precede packages and skipped groups leave room`() {
        val native = bundled(CatalogAdmission.LIMIT - 5)
        val installed = service()
        val links = group("extension.package.aaa-paseo-links", 3, "package:aaa-paseo-links", "Paseo")
        val media = group("extension.media.zzz", 2, "media:0:zzz")
        val selected = CatalogAdmission.select(links + media + installed + native)
        assertEquals(native + installed + media, selected.admitted)
        assertEquals(links, selected.overflow)
        assertEquals(CatalogAdmission.LIMIT, selected.admitted.size)
    }

    @Test
    fun `package instances with the same title remain separate atomic groups`() {
        val native = bundled(CatalogAdmission.LIMIT - 3)
        val first = group("extension.package.first", 2, "package:first", "Maps")
        val second = group("extension.package.second", 2, "package:second", "Maps")
        val selected = CatalogAdmission.select(native + first + second)
        assertEquals(native + first, selected.admitted)
        assertEquals(second, selected.overflow)
        assertEquals(CatalogAdmission.LIMIT - 1, selected.admitted.size)
    }

    @Test
    fun `source identity groups tools and missing metadata falls back to the full extension prefix`() {
        val native = bundled(CatalogAdmission.LIMIT - 1)
        val installed = service().take(2).mapIndexed { index, tool -> tool.copy(id = "extension.alias-$index.operation") }
        val legacy = List(2) { definition("extension.legacy.app.operation_$it") }
        val smaller = listOf(definition("extension.zzz.app.operation"))
        val selected = CatalogAdmission.select(native + installed + legacy + smaller)
        assertEquals(native + smaller, selected.admitted)
        assertEquals((installed + legacy).toSet(), selected.overflow.toSet())
    }

    @Test
    fun `oversized group does not prevent smaller later groups being admitted`() {
        val oversized = group("extension.package.aaa", CatalogAdmission.LIMIT + 1, "package:aaa")
        val smaller = group("extension.package.zzz", 2, "package:zzz")
        val selected = CatalogAdmission.select(oversized + smaller)
        assertEquals(smaller, selected.admitted)
        assertEquals(oversized, selected.overflow)
    }

    @Test
    fun `admitted tools overflow and reasons are deterministic across input order`() {
        val definitions =
            bundled(26) + service() + List(200) { group("extension.package.$it", 3, "package:$it") }.flatten() +
                group("extension.media.player", 3, "media:0:player")
        val expected = CatalogAdmission.select(definitions, controls = 4)
        val reasons = CatalogAdmission.overflowReasons(definitions)
        repeat(20) { seed ->
            val shuffled = definitions.shuffled(Random(seed))
            assertEquals(expected, CatalogAdmission.select(shuffled, controls = 4))
            assertEquals(reasons.entries.toList(), CatalogAdmission.overflowReasons(shuffled).entries.toList())
        }
        assertTrue(expected.admitted.size <= CatalogAdmission.LIMIT - 4)
        assertEquals(definitions.toSet(), (expected.admitted + expected.overflow).toSet())
    }

    @Test
    fun `voice reserves four session controls and two more for an offered device task`() {
        val native = bundled(CatalogAdmission.LIMIT)
        assertEquals(4, CatalogAdmission.voiceControls(native))
        val voice = CatalogAdmission.select(native, CatalogAdmission.voiceControls(native), voiceBytes = Int.MAX_VALUE)
        assertEquals(CatalogAdmission.LIMIT - 4, voice.admitted.size)
        val withTask = native.take(CatalogAdmission.LIMIT - 1) + definition(CapabilityRegistry.DEVICE_TASK)
        assertEquals(6, CatalogAdmission.voiceControls(withTask))
        assertEquals(CatalogAdmission.LIMIT - 6, CatalogAdmission.select(withTask, 6, voiceBytes = Int.MAX_VALUE).admitted.size)
    }

    @Test
    fun `voice enforces metadata bytes instead of an arbitrary low tool count`() {
        val compact = bundled(200)
        val voice = CatalogAdmission.select(compact, controls = 4)
        assertTrue(voice.admitted.size > 128)
        assertTrue(voice.metadataBytes <= CatalogAdmission.VOICE_TOOL_BYTES)
        val large = service().map { it.copy(description = "é".repeat(40_000)) }
        val later = group("extension.package.zzz", 1, "package:zzz")
        val selected = CatalogAdmission.select(large + later, controls = 4)
        assertEquals(later, selected.admitted)
        assertEquals(large, selected.overflow)
        assertTrue(CatalogAdmission.select(large + later).overflow.isEmpty())
        val preview = CatalogAdmission.preview(large + later)
        assertEquals(selected, preview.voice)
        assertTrue(
            CatalogAdmission.overflowReasons(preview).values.all {
                it.contains("Unavailable in voice") && it.contains("session controls") && it.contains("224 KiB")
            },
        )
    }

    @Test
    fun `shared extension guidance consumes bytes once per admitted source`() {
        val workflow = service().map { it.copy(guidance = "g".repeat(100_000)) }
        val selection = CatalogAdmission.select(workflow, controls = 4)
        assertEquals(workflow, selection.admitted)
        assertTrue(selection.overflow.isEmpty())
        assertTrue(selection.metadataBytes in 100_000..110_000)
    }

    @Test
    fun `text safety bound is the highest verified count and prompt-hidden actions are not reported as overflow`() {
        assertEquals(512, CatalogAdmission.LIMIT)
        val definitions = bundled(513)
        val selected = CatalogAdmission.select(definitions)
        assertEquals(512, selected.admitted.size)
        assertEquals(definitions.last(), selected.overflow.single())
        val visible = selected.without(setOf(definitions.last().id, definitions.first().id))
        assertTrue(visible.overflow.isEmpty())
        assertEquals(511, visible.admitted.size)
    }

    @Test
    fun `overflow reasons account for groups fitting only in voice after a larger group is skipped`() {
        val native = bundled(CatalogAdmission.LIMIT - 6)
        val large = group("extension.package.aaa", 6, "package:aaa")
        val small = group("extension.package.bbb", 2, "package:bbb")
        val alwaysOverflow = group("extension.package.ccc", 7, "package:ccc")
        val definitions = native + large + small + alwaysOverflow
        assertEquals(native + large, CatalogAdmission.select(definitions).admitted)
        assertEquals(native + small, CatalogAdmission.select(definitions, controls = 4, voiceBytes = Int.MAX_VALUE).admitted)
        val reasons =
            CatalogAdmission.overflowReasons(
                CatalogAdmission.Preview(
                    CatalogAdmission.select(definitions),
                    CatalogAdmission.select(definitions, controls = 4, voiceBytes = Int.MAX_VALUE),
                ),
            )
        assertEquals((large + small + alwaysOverflow).map { it.id }.toSet(), reasons.keys)
        assertTrue(large.all { reasons.getValue(it.id).startsWith("Unavailable in voice.") })
        assertTrue(small.all { reasons.getValue(it.id).startsWith("Unavailable in typed conversations.") })
        assertTrue(alwaysOverflow.all { reasons.getValue(it.id).startsWith("Unavailable in typed and voice conversations.") })
        assertTrue(reasons.values.all { it.contains("whole groups") && it.contains("installed services") })
        assertFalse(reasons.values.any { it.contains("limit is full") })
    }

    @Test
    fun `fully reserved catalog admits nothing and rejects invalid reservations`() {
        val definitions = bundled(2) + service()
        val selected = CatalogAdmission.select(definitions, controls = CatalogAdmission.LIMIT)
        assertTrue(selected.admitted.isEmpty())
        assertEquals(definitions.toSet(), selected.overflow.toSet())
        for (controls in listOf(-1, CatalogAdmission.LIMIT + 1)) {
            assertTrue(runCatching { CatalogAdmission.select(definitions, controls) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}

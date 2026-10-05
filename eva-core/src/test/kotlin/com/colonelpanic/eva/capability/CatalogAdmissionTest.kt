package com.colonelpanic.eva.capability

import com.colonelpanic.eva.capability.extensions.extensionSchema
import com.colonelpanic.eva.providers.ExcludedTool
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
    fun `voice reserves five control slots and settings distinguishes typed and voice overflow`() {
        val native = bundled(CatalogAdmission.LIMIT - 10)
        val extensions = List(20) { definition("extension.test-${it.toString().padStart(2, '0')}.action") }
        val definitions = native + extensions
        val voice = CatalogAdmission.select(definitions, controls = CatalogAdmission.voiceControls(definitions))
        assertEquals(CatalogAdmission.LIMIT - 5, voice.admitted.size)
        val reasons = CatalogAdmission.overflowReasons(definitions)
        assertEquals(15, reasons.size)
        assertTrue(reasons.getValue(extensions[6].id).contains("Available in typed"))
        assertTrue(reasons.getValue(extensions[9].id).contains("Available in typed"))
        assertTrue(reasons.getValue(extensions[10].id).startsWith("Unavailable in typed and voice"))
        assertTrue(CatalogAdmission.overflowReasons(native + extensions.take(5)).isEmpty())
        val withTasks = listOf(definition(CapabilityRegistry.DEVICE_TASK)) + definitions
        assertEquals(7, CatalogAdmission.voiceControls(withTasks))
        assertEquals(CatalogAdmission.LIMIT - 7, CatalogAdmission.select(withTasks, 7).admitted.size)
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
        assertTrue(expected.admitted.size <= CatalogAdmission.LIMIT - 5)
        assertEquals(definitions.toSet(), (expected.admitted + expected.overflow).toSet())
    }

    @Test
    fun `voice reserves five session controls and two more for an offered device task`() {
        val native = bundled(CatalogAdmission.LIMIT)
        assertEquals(5, CatalogAdmission.voiceControls(native))
        val voice = CatalogAdmission.select(native, CatalogAdmission.voiceControls(native))
        assertEquals(CatalogAdmission.LIMIT - 5, voice.admitted.size)
        val withTask = native.take(CatalogAdmission.LIMIT - 1) + definition(CapabilityRegistry.DEVICE_TASK)
        assertEquals(7, CatalogAdmission.voiceControls(withTask))
        assertEquals(CatalogAdmission.LIMIT - 7, CatalogAdmission.select(withTask, 7).admitted.size)
    }

    @Test
    fun `voice offers large tool metadata because the call request carries it whole`() {
        val large = service().map { it.copy(description = "é".repeat(40_000), guidance = "g".repeat(100_000)) }
        val later = group("extension.package.zzz", 1, "package:zzz")
        val preview = CatalogAdmission.preview(large + later)
        assertEquals(large + later, preview.voice.admitted)
        assertTrue(preview.voice.overflow.isEmpty())
        assertTrue(CatalogAdmission.overflowReasons(preview).isEmpty())
    }

    @Test
    fun `prompt-hidden actions leave before admission so they take no capacity in either mode`() {
        val native = bundled(CatalogAdmission.LIMIT - 5)
        val hiddenGroup = group("extension.package.aaa", 3, "package:aaa")
        val wanted = group("extension.package.bbb", 3, "package:bbb")
        val definitions = native + hiddenGroup + wanted
        val hidden = hiddenGroup.map { it.id }.toSet()
        val shown = CatalogAdmission.preview(definitions)
        assertEquals(wanted, shown.text.overflow)
        val preview = CatalogAdmission.preview(definitions, textHidden = hidden, voiceHidden = hidden + native.first().id)
        assertEquals(native + wanted, preview.text.admitted)
        assertTrue(preview.text.overflow.isEmpty())
        // Voice reserves five session controls, so the wanted group still does not fit there.
        assertEquals(native.drop(1), preview.voice.admitted)
        assertEquals(wanted.map { ExcludedTool(it.id, it.title) }, preview.voice.excluded())
        assertTrue(CatalogAdmission.overflowReasons(preview).keys == wanted.map { it.id }.toSet())
    }

    @Test
    fun `text safety bound is the highest verified count`() {
        assertEquals(512, CatalogAdmission.LIMIT)
        val definitions = bundled(513)
        val selected = CatalogAdmission.select(definitions)
        assertEquals(512, selected.admitted.size)
        assertEquals(definitions.last(), selected.overflow.single())
    }

    @Test
    fun `overflow reasons account for groups fitting only in voice after a larger group is skipped`() {
        val native = bundled(CatalogAdmission.LIMIT - 7)
        val large = group("extension.package.aaa", 6, "package:aaa")
        val small = group("extension.package.bbb", 2, "package:bbb")
        val alwaysOverflow = group("extension.package.ccc", 7, "package:ccc")
        val definitions = native + large + small + alwaysOverflow
        assertEquals(native + large, CatalogAdmission.select(definitions).admitted)
        assertEquals(native + small, CatalogAdmission.select(definitions, controls = 4).admitted)
        val reasons =
            CatalogAdmission.overflowReasons(
                CatalogAdmission.Preview(
                    CatalogAdmission.select(definitions),
                    CatalogAdmission.select(definitions, controls = 4),
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

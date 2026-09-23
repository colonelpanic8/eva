package com.colonelpanic.eva.capability

import com.colonelpanic.eva.capability.extensions.extensionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAdmissionTest {
    private fun definition(id: String) = CapabilityDefinition(id, id, id, extensionSchema)

    private val bundled = List(26) { definition("eva.bundled.${it.toString().padStart(2, '0')}") }
    private val extensions = List(64) { definition("extension.example.action_${it.toString().padStart(2, '0')}") }

    @Test
    fun `bundled tools keep priority and extensions have deterministic overflow`() {
        val first = CatalogAdmission.select((extensions + bundled).reversed())
        val second = CatalogAdmission.select(bundled + extensions)
        assertEquals(first, second)
        assertEquals(64, first.admitted.size)
        assertEquals(bundled, first.admitted.take(26))
        assertEquals(extensions.take(38), first.admitted.drop(26))
        assertEquals(extensions.drop(38), first.overflow)
    }

    @Test
    fun `voice reserves two control slots and settings distinguishes typed and voice overflow`() {
        val voice = CatalogAdmission.select(bundled + extensions, controls = 2)
        assertEquals(62, voice.admitted.size)
        val reasons = CatalogAdmission.overflowReasons(bundled + extensions)
        assertEquals(28, reasons.size)
        assertTrue(reasons.getValue(extensions[36].id).contains("Available in typed"))
        assertTrue(reasons.getValue(extensions[37].id).contains("Available in typed"))
        assertTrue(reasons.getValue(extensions[38].id).startsWith("Unavailable:"))
        assertTrue(CatalogAdmission.overflowReasons((bundled + extensions).take(62)).isEmpty())
    }
}

package com.colonelpanic.eva.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTaskBackendsTest {
    @Test
    fun reordersAndTogglesWithoutLeavingNoBackend() {
        assertEquals(listOf("shizuku", "portal"), moveBackend(listOf("portal", "shizuku"), "shizuku", -1))
        assertEquals(listOf("portal", "shizuku"), moveBackend(listOf("portal", "shizuku"), "portal", -1))
        assertEquals(listOf("shizuku"), toggleBackend(listOf("portal", "shizuku"), "portal"))
        assertEquals(listOf("shizuku"), toggleBackend(listOf("shizuku"), "shizuku"))
        assertEquals(listOf("shizuku", "portal"), toggleBackend(listOf("shizuku"), "portal"))
        assertEquals(listOf("shizuku", "portal"), backendRows(listOf("shizuku")))
    }
}

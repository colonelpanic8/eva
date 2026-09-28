package com.colonelpanic.eva.devicecontrol.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectionTest {
    @Test fun physicalGuardRequiresMatchingSerialAndQemuProperty() =
        runBlocking {
            var property = "0"
            val adb = Adb { property }
            try {
                verifyDevice(adb, "emulator-5594", null)
                fail("spoofed emulator accepted")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(verifyDevice(adb, "phone", "phone"))
            property = "1"
            assertTrue(verifyDevice(adb, "emulator-5594", null))
        }

    @Test fun tokenParserHandlesPortalEnvelopeWithoutLeakingFailures() {
        assertEquals("test-only-token", portalToken("""Row: 0 result={"status":"success","result":"test-only-token"}"""))
        val failure = assertThrows(IllegalStateException::class.java) { portalToken("Row: 0 result={secret-token-but-broken") }
        assertFalse(failure.message.orEmpty().contains("secret-token"))
    }
}

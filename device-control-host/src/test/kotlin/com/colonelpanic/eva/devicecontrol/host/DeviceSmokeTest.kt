package com.colonelpanic.eva.devicecontrol.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class DeviceSmokeTest {
    @Test fun observeAndHomeOnDedicatedEmulator() =
        runBlocking {
            val serial = System.getenv("EVA_HOST_TEST_SERIAL")
            assumeTrue("Opt in with EVA_HOST_TEST_SERIAL=emulator-5594", serial == "emulator-5594")
            val adb = ProcessAdb(serial)
            val session = DeviceSession.connect(adb, serial, null, Files.createTempDirectory("eva-host-device"))
            try {
                val backend = session.backend()
                assertTrue(backend.observe().screen.width > 0)
                val result = backend.perform(bindAction(backend, Json.parseToJsonElement("""{"kind":"home"}""").jsonObject))
                assertTrue(result.ok)
                assertTrue(requireNotNull(result.observation).screenOn)
            } finally {
                session.disconnect()
            }
        }
}

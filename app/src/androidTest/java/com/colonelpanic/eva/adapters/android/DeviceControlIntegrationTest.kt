package com.colonelpanic.eva.adapters.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class DeviceControlIntegrationTest {
    @Test
    fun observeSetTextAndTapAcrossApps() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertEquals(
                "Grant EVA access in Shizuku before running this device test",
                PackageManager.PERMISSION_GRANTED,
                Shizuku.checkSelfPermission(),
            )
            val host = DeviceControlHost(context)
            val fixture = checkNotNull(context.packageManager.getLaunchIntentForPackage(FIXTURE_PACKAGE))
            fixture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(fixture)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)

            assertEquals(null, host.unavailableReason())
            val observations = ObservationStore(SystemClock::elapsedRealtime)
            val observe = UiControlBackend(host, observations, UiControlBackend.Operation.OBSERVE)
            val setText = UiControlBackend(host, observations, UiControlBackend.Operation.SET_TEXT)
            val tap = UiControlBackend(host, observations, UiControlBackend.Operation.TAP)

            val first = observe.execute(emptyMap())
            assertEquals(first.message, InvocationStatus.COMPLETED, first.status)
            val firstReference = reference(first.message)
            val input = element(first.message, "Probe text")
            val textResult =
                setText.execute(
                    mapOf(
                        "observationRef" to firstReference,
                        "node" to input.toString(),
                        "text" to "EVA integration ✓",
                    ),
                )
            assertEquals(textResult.message, InvocationStatus.COMPLETED, textResult.status)
            assertTrue(textResult.message.contains("EVA integration ✓"))

            val afterTextReference = reference(textResult.message)
            val increment = element(textResult.message, "Increment counter")
            val tapResult =
                tap.execute(
                    mapOf(
                        "observationRef" to afterTextReference,
                        "node" to increment.toString(),
                    ),
                )
            assertEquals(tapResult.message, InvocationStatus.COMPLETED, tapResult.status)
            assertTrue(tapResult.message.contains("Count: 1"))
        }

    private fun reference(projection: String): String = checkNotNull(Regex("\\(observation ([^)]+)\\)").find(projection)).groupValues[1]

    private fun element(
        projection: String,
        label: String,
    ): Int {
        val line = checkNotNull(projection.lineSequence().firstOrNull { it.contains("\"$label\"", ignoreCase = true) })
        return checkNotNull(Regex("^\\[(\\d+)]").find(line)).groupValues[1].toInt()
    }

    private companion object {
        const val FIXTURE_PACKAGE = "com.colonelpanic.eva.devicefixture"
    }
}

package com.colonelpanic.eva

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.colonelpanic.eva.adapters.android.AppFunctionCommands
import com.colonelpanic.eva.adapters.android.DeviceStateCategory
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class AppFunctionsDeviceTest {
    private lateinit var eva: EvaApplication

    @Before
    fun launchEva() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        eva = context.applicationContext as EvaApplication
    }

    @Test
    fun permissionDeniedIsReported() =
        runBlocking {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val deniedRequest =
                async(Dispatchers.Default) {
                    backend(CapabilityRegistry.DEVICE_STATE_GET).execute(mapOf("category" to "battery"))
                }
            val deny = device.wait(Until.findObject(By.text("Deny")), 10_000)
            checkNotNull(deny) { "Shizuku denial button did not appear" }.click()
            val denied = deniedRequest.await()
            evidence("permission-denied", denied.status.name, denied.message)
            assertEquals(InvocationStatus.NOT_EXECUTED, denied.status)
            assertTrue(denied.message.contains("denied", ignoreCase = true))
        }

    @Test
    fun typedCapabilitiesRunThroughShellUserService() =
        runBlocking {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val allowedRequest =
                async(Dispatchers.Default) {
                    backend(CapabilityRegistry.DEVICE_STATE_GET).execute(mapOf("category" to "battery"))
                }
            val allow = device.wait(Until.findObject(By.text("Allow all the time")), 10_000)
            checkNotNull(allow) { "Shizuku allow button did not appear" }.click()
            val allowed = measuredResult("permission-allowed", allowedRequest.await())
            assertEquals(InvocationStatus.COMPLETED, allowed.status)

            val metadata =
                measured("metadata-dark") {
                    backend(CapabilityRegistry.DEVICE_STATE_METADATA).execute(mapOf("search" to "dark"))
                }
            assertEquals(InvocationStatus.COMPLETED, metadata.status)
            assertTrue(metadata.message.contains("dark_ui_mode/dark_ui_activated"))

            val read =
                measured("read-uncategorized") {
                    backend(CapabilityRegistry.DEVICE_STATE_GET).execute(mapOf("category" to "uncategorized"))
                }
            assertEquals(InvocationStatus.COMPLETED, read.status)
            assertTrue(read.message.contains("dark_ui_mode/dark_ui_activated"))

            val enable =
                measured("set-dark-true") {
                    backend(CapabilityRegistry.DEVICE_STATE_SET).execute(
                        mapOf("key" to "dark_ui_mode/dark_ui_activated", "value" to "true"),
                    )
                }
            assertEquals(InvocationStatus.COMPLETED, enable.status)
            assertTrue(enable.message.contains("Current value: true"))

            val restore =
                measured("set-dark-false") {
                    backend(CapabilityRegistry.DEVICE_STATE_SET).execute(
                        mapOf("key" to "dark_ui_mode/dark_ui_activated", "value" to "false"),
                    )
                }
            assertEquals(InvocationStatus.COMPLETED, restore.status)
            assertTrue(restore.message.contains("Current value: false"))

            val timeout =
                measuredShell("host-timeout") {
                    checkNotNull(eva.shizukuShellHost).run(
                        AppFunctionCommands.getState(DeviceStateCategory.APPS),
                        timeoutMillis = 1,
                    )
                }
            assertTrue(timeout.timedOut)
            assertEquals(2000, timeout.uid)
        }

    @Test
    fun everyExposedReadCategoryCompletes() =
        runBlocking {
            listOf("storage", "notifications", "apps", "mobile_data").forEach { category ->
                val result =
                    measured("read-$category") {
                        backend(CapabilityRegistry.DEVICE_STATE_GET).execute(mapOf("category" to category))
                    }
                assertEquals(InvocationStatus.COMPLETED, result.status)
            }
        }

    private fun backend(id: String): ExecutionBackend =
        checkNotNull(
            eva.registry.resolve(
                com.colonelpanic.eva.capability
                    .ToolProposal("device-test", id, emptyMap(), "test", eva.registry.snapshot.revision),
            ),
        )

    private suspend fun measured(
        name: String,
        block: suspend () -> com.colonelpanic.eva.capability.ExecutionOutcome,
    ): com.colonelpanic.eva.capability.ExecutionOutcome {
        val started = SystemClock.elapsedRealtime()
        val outcome = block()
        evidence(name, outcome.status.name, "${SystemClock.elapsedRealtime() - started}ms ${outcome.message}")
        return outcome
    }

    private fun measuredResult(
        name: String,
        outcome: com.colonelpanic.eva.capability.ExecutionOutcome,
    ): com.colonelpanic.eva.capability.ExecutionOutcome {
        evidence(name, outcome.status.name, outcome.message)
        return outcome
    }

    private suspend fun measuredShell(
        name: String,
        block: suspend () -> com.colonelpanic.eva.adapters.android.ShellResult,
    ): com.colonelpanic.eva.adapters.android.ShellResult {
        val started = SystemClock.elapsedRealtime()
        val result = block()
        evidence(
            name,
            "exit=${result.exitCode}",
            "${SystemClock.elapsedRealtime() - started}ms timedOut=${result.timedOut} uid=${result.uid}",
        )
        return result
    }

    private fun evidence(
        name: String,
        status: String,
        message: String,
    ) {
        Log.i(TAG, "$name | $status | ${message.replace('\n', ' ').take(1_000)}")
        println("$TAG | $name | $status | ${message.replace('\n', ' ').take(1_000)}")
    }

    private companion object {
        const val TAG = "EvaAppFunctionsTest"
    }
}

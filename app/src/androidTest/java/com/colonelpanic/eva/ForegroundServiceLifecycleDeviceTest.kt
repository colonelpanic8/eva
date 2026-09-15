package com.colonelpanic.eva

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.conversation.TurnWorkService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceLifecycleDeviceTest {
    @Test
    fun rapidStopRestartAndStopWaitsForTheReplacementService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync { TurnWorkService.start(context) }
        waitUntil("TurnWorkService did not enter the foreground") { service(context)?.foreground == true }

        instrumentation.runOnMainSync {
            TurnWorkService.stop(context)
            TurnWorkService.start(context)
            TurnWorkService.stop(context)
        }

        waitUntil("TurnWorkService did not stop after its replacement was foregrounded") { service(context) == null }
        Thread.sleep(12_000)
    }

    @Suppress("DEPRECATION")
    private fun service(context: Context): ActivityManager.RunningServiceInfo? =
        context
            .getSystemService(ActivityManager::class.java)
            .getRunningServices(100)
            .firstOrNull { it.service.className == TurnWorkService::class.java.name }

    private fun waitUntil(
        failure: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && !condition()) Thread.sleep(20)
        assertTrue(failure, condition())
    }
}

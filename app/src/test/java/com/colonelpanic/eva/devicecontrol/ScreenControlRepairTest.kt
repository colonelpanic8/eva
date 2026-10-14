package com.colonelpanic.eva.devicecontrol

import android.content.ComponentName
import android.provider.Settings
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.devicecontrol.portal.PortalHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class FakeShizuku(
    var status: String = DeviceControlHost.ALLOWED,
) : ShizukuHelper {
    val enabled = mutableListOf<Pair<ComponentName, Boolean>>()
    var restarts = 0
    var onEnable: () -> Unit = {}

    override suspend fun accessStatus() = status

    override suspend fun requestAccess() = status

    override suspend fun restartHelper() {
        restarts++
    }

    override suspend fun enableAccessibilityService(
        component: ComponentName,
        restart: Boolean,
    ) {
        enabled += component to restart
        onEnable()
    }
}

@RunWith(RobolectricTestRunner::class)
class ScreenControlRepairTest {
    private val portal = ComponentName("com.mobilerun.portal", "com.mobilerun.portal.service.MobilerunAccessibilityService")
    private val shizuku = FakeShizuku()
    private val verified = mutableListOf<String>()
    private var taskRunning = false
    private var elapsed = 0L

    private fun repair(
        health: () -> PortalHealth?,
        service: PortalService? = PortalService(portal, enabled = false),
    ) = ScreenControlRepair(
        context = RuntimeEnvironment.getApplication(),
        helper = { shizuku },
        portalHealth = health,
        portalPort = { 8080 },
        verify = { backend ->
            verified += backend
            null
        },
        taskRunning = { taskRunning },
        portalService = { service },
        portalStartMillis = 2_000,
        elapsedMillis = { elapsed },
    )

    @Test
    fun `a stopped Portal is restarted through Shizuku and confirmed by a screen read`() =
        runTest {
            var checks = 0
            val outcome =
                repair({ if (checks++ < 2) PortalHealth.UNREACHABLE else PortalHealth.READY }, PortalService(portal, enabled = true))
                    .repair("portal")

            assertEquals(listOf(portal to true), shizuku.enabled)
            assertEquals(listOf("portal"), verified)
            assertEquals(RepairOutcome.Fixed("Restarted Portal's accessibility service. It read the screen."), outcome)
        }

    @Test
    fun `without Shizuku a stopped Portal sends the user to accessibility settings`() =
        runTest {
            shizuku.status = DeviceControlHost.NOT_ALLOWED
            val outcome = repair({ PortalHealth.UNREACHABLE }).repair("portal")

            assertTrue(shizuku.enabled.isEmpty())
            assertTrue(outcome is RepairOutcome.Open)
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, (outcome as RepairOutcome.Open).intent.action)
        }

    @Test
    fun `a Portal that stays silent after its service is turned on is restarted, then opened for the user`() =
        runTest {
            val outcome = repair({ PortalHealth.UNREACHABLE }).repair("portal")

            assertEquals(listOf(portal to false, portal to true), shizuku.enabled)
            assertTrue(verified.isEmpty())
            assertTrue(outcome is RepairOutcome.Open)
            assertTrue(outcome.message, outcome.message.contains("still not answering on port 8080"))
        }

    @Test
    fun `a Portal that answers but cannot read the screen is restarted, then opened for the user`() =
        runTest {
            val repair =
                ScreenControlRepair(
                    context = RuntimeEnvironment.getApplication(),
                    helper = { shizuku },
                    portalHealth = { PortalHealth.READY },
                    portalPort = { 8080 },
                    verify = { "it did not read the screen within 10 seconds." },
                    taskRunning = { false },
                    portalService = { PortalService(portal, enabled = true) },
                    portalStartMillis = 2_000,
                )

            val outcome = repair.repair("portal")

            assertEquals(listOf(portal to true), shizuku.enabled)
            assertTrue(outcome is RepairOutcome.Open)
            assertTrue(outcome.message, outcome.message.startsWith("Restarted Portal's accessibility service, but it did not read"))
        }

    @Test
    fun `a Portal service removed by a force stop is put back without a tap, at most once a minute`() =
        runTest {
            var listed = false
            shizuku.onEnable = { listed = true }
            val repair =
                ScreenControlRepair(
                    context = RuntimeEnvironment.getApplication(),
                    helper = { shizuku },
                    portalHealth = { if (listed) PortalHealth.READY else PortalHealth.UNREACHABLE },
                    portalPort = { 8080 },
                    verify = { null },
                    taskRunning = { false },
                    portalService = { PortalService(portal, enabled = listed) },
                    portalStartMillis = 2_000,
                    elapsedMillis = { elapsed },
                )

            assertTrue(repair.restorePortal())
            listed = false
            elapsed += 30_000
            assertFalse(repair.restorePortal())
            elapsed += 30_000
            listed = false
            assertTrue(repair.restorePortal())
            assertEquals(listOf(portal to false, portal to false), shizuku.enabled)
        }

    @Test
    fun `a listed but silent Portal is left for a tap`() =
        runTest {
            assertFalse(repair({ PortalHealth.UNREACHABLE }, PortalService(portal, enabled = true)).restorePortal())
            assertTrue(shizuku.enabled.isEmpty())
        }

    @Test
    fun `the Shizuku helper is not restarted under a running device task`() =
        runTest {
            taskRunning = true
            val outcome = repair({ PortalHealth.READY }).repair("shizuku")

            assertEquals(0, shizuku.restarts)
            assertTrue(outcome is RepairOutcome.Failed)
        }
}

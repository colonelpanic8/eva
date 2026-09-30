package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.kind
import com.colonelpanic.eva.devicecontrol.testing.FakeDeviceBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PreferredDeviceBackendTest {
    private val screen = Observation("o1", "now", "fake", screen = Screen(100, 200, Orientation.PORTRAIT))

    private fun phone() =
        FakeDeviceBackend(screen) { action ->
            ActionResult(action.actionId, action.kind, true, "now", "now", screen, executionStatus = ExecutionStatus.EXECUTED)
        }

    private fun broken(
        message: String,
        observed: MutableList<String>,
        name: String,
    ) = object : DeviceBackend {
        override suspend fun observe(): Observation {
            observed += name
            throw IOException(message)
        }

        override suspend fun perform(action: com.colonelpanic.eva.devicecontrol.proto.Action): ActionResult = error("unused")
    }

    @Test fun skipsAnUnreadyBackendAndFallsBackWhenTheNextCannotReadTheScreen() =
        runTest {
            val observed = mutableListOf<String>()
            val shizuku = phone()
            val backend =
                PreferredDeviceBackend(
                    listOf(
                        PreferredDeviceBackend.Candidate("Portal", { "Portal is not running." }) { error("not opened") },
                        PreferredDeviceBackend.Candidate("Stale", { null }) { broken("socket closed", observed, "Stale") },
                        PreferredDeviceBackend.Candidate("Shizuku", { null }) { shizuku },
                    ),
                )
            assertEquals(screen, backend.observe())
            assertEquals("Shizuku", backend.active)
            assertEquals(listOf("Stale"), observed)
            assertEquals(1, shizuku.observations)
        }

    @Test fun anActionPinsTheBackendSoLaterFailuresDoNotMoveTheTask() =
        runTest {
            var failing = false
            val first =
                object : DeviceBackend {
                    val phone = phone()

                    override suspend fun observe(): Observation = if (failing) throw IOException("lost") else phone.observe()

                    override suspend fun perform(action: com.colonelpanic.eva.devicecontrol.proto.Action) = phone.perform(action)
                }
            val second = phone()
            val backend =
                PreferredDeviceBackend(
                    listOf(
                        PreferredDeviceBackend.Candidate("First", { null }) { first },
                        PreferredDeviceBackend.Candidate("Second", { null }) { second },
                    ),
                )
            backend.observe()
            backend.perform(
                com.colonelpanic.eva.devicecontrol.proto
                    .Home("a1", "task", 0, "o1"),
            )
            failing = true
            assertTrue(runCatching { backend.observe() }.exceptionOrNull() is IOException)
            assertEquals(0, second.observations)
        }

    @Test fun reportsEveryBackendsReasonWhenNoneCanServe() =
        runTest {
            val backend =
                PreferredDeviceBackend(
                    listOf(
                        PreferredDeviceBackend.Candidate("Portal", { "Portal is not running." }) { error("not opened") },
                        PreferredDeviceBackend.Candidate("Shizuku", { null }) { broken("already registered", mutableListOf(), "Shizuku") },
                    ),
                )
            val message = runCatching { backend.observe() }.exceptionOrNull()?.message.orEmpty()
            assertTrue(message, message.contains("Portal: Portal is not running.") && message.contains("Shizuku: already registered"))
        }
}

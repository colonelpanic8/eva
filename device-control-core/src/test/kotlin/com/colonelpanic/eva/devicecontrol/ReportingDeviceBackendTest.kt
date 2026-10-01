package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.BackendUnavailable
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.ProtectedContent
import com.colonelpanic.eva.devicecontrol.proto.ProtectedReason
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ReportingDeviceBackendTest {
    private val screen = Observation("o1", "now", "fake", screen = Screen(100, 200, Orientation.PORTRAIT))
    private val reports = mutableListOf<String?>()
    private var read: () -> Observation = { screen }
    private var result: (com.colonelpanic.eva.devicecontrol.proto.Action) -> ActionResult = { action ->
        ActionResult(action.actionId, action.kind, true, "now", "now", screen)
    }
    private val backend =
        ReportingDeviceBackend(
            "Shizuku",
            object : DeviceBackend {
                override suspend fun observe() = read()

                override suspend fun perform(action: com.colonelpanic.eva.devicecontrol.proto.Action) = result(action)
            },
        ) { reports += it }

    @Test
    fun readsAndInputsReportTheBackendsHealthButNotTheScreensState() =
        runTest {
            backend.observe()
            read = { throw IOException("helper didn't connect") }
            runCatching { backend.observe() }
            read = { throw ObservationFailure(ProtectedContent(reason = ProtectedReason.SECURE_WINDOW)) }
            runCatching { backend.observe() }
            result = { action ->
                ActionResult(
                    action.actionId,
                    action.kind,
                    false,
                    "now",
                    "now",
                    null,
                    error = BackendUnavailable(backend = "shizuku"),
                    executionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
                )
            }
            backend.perform(Back("a1", "t", 0, "o1"))
            // A plain successful read clears the warning, with no input needed.
            read = { screen }
            backend.observe()

            assertEquals(
                listOf(
                    null,
                    "Shizuku couldn't read the screen: helper didn't connect",
                    "Shizuku couldn't deliver the input: the shizuku backend is unavailable",
                    null,
                ),
                reports,
            )
        }
}

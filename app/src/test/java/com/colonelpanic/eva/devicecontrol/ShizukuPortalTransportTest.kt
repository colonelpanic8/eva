package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.portal.PortalBackend
import com.colonelpanic.eva.devicecontrol.proto.AppNotFound
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.LaunchApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPortalTransportTest {
    private val state =
        """{"ok":true,"state":{"a11y_tree":{"className":"android.widget.TextView","text":"Home","boundsInScreen":""" +
            """{"left":0,"top":0,"right":100,"bottom":50},"isVisibleToUser":true,"children":[]},""" +
            """"phone_state":{"packageName":"app.launcher"},"device_context":{"screen_bounds":{"width":100,"height":200}}}}"""

    @Test fun helperRefusalOfAMissingAppIsNotAnUncertainLaunch() =
        runTest {
            val backend =
                PortalBackend(
                    ShizukuPortalTransport(
                        { state },
                        { _, _ -> """{"ok":false,"reason":"error","detail":"Failure: com.missing.app not found"}""" },
                        { error("unused") },
                    ),
                    sleep = {},
                    backend = "shizuku",
                )
            val before = backend.observe()
            assertEquals("shizuku", before.backend)
            val result = backend.perform(LaunchApp("a", "task", 0, before.observationId, "com.missing.app"))
            assertTrue(result.error is AppNotFound)
            assertEquals(ExecutionStatus.NOT_DISPATCHED, result.executionStatus)
        }
}

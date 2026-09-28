package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.Home
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.Unsupported
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuDeviceBackendTest {
    private fun screen(
        text: String = "old",
        password: Boolean = false,
    ) =
        """{"ok":true,"delivered":true,"observation":{"package":"app.test","width":100,"height":200,"rotation":0,"nodes":[{"i":0,"cls":"android.widget.EditText","text":"$text","desc":"field","l":0,"t":0,"r":100,"b":100,"editable":true,"clickable":true,"password":$password}]}}"""

    @Test fun unsupportedActionsNeverReachTheHelper() =
        runTest {
            var inputs = 0
            val backend =
                ShizukuDeviceBackend({ screen() }) {
                    inputs++
                    screen()
                }
            val before = backend.observe()
            val result = backend.perform(Home("a", "task", 0, before.observationId))
            assertTrue(result.error is Unsupported)
            assertEquals(0, inputs)
        }

    @Test fun textIsVerifiedAndTargetExpectationUsesRecordedTree() =
        runTest {
            var request = Json.parseToJsonElement("{}").jsonObject
            val backend =
                ShizukuDeviceBackend({ screen() }) {
                    request = it
                    screen("new")
                }
            val before = backend.observe()
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 0, "new"))
            assertTrue(result.ok)
            assertEquals(SetTextDetails(0, true, "new", "new"), result.details)
            assertTrue(request.getValue("expect").toString().contains("old"))
            val stale = backend.perform(ActivateElement("b", "task", 0, before.observationId, 0))
            assertFalse(stale.ok)
        }

    @Test fun passwordAndUnsupportedAppendNeverLeakOrExecute() =
        runTest {
            var inputs = 0
            val backend =
                ShizukuDeviceBackend({ screen("secret", true) }) {
                    inputs++
                    screen()
                }
            val before = backend.observe()
            assertEquals(null, before.elements.single().text)
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 0, "value"))
            assertTrue(result.error is Unsupported)
            assertEquals(0, inputs)
        }
}

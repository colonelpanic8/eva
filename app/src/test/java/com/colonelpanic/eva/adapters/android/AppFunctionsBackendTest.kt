package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.InvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFunctionsBackendTest {
    @Test
    fun `commands keep function ids and nested setter parameters out of shell syntax`() {
        assertEquals(
            listOf(
                "app_function",
                "execute-app-function",
                "--package",
                "com.android.settings",
                "--function",
                "getBatteryDeviceState",
                "--parameters",
                "{}",
                "--timeout-duration",
                "8",
                "--brief-yaml",
            ),
            AppFunctionCommands.getState(DeviceStateCategory.BATTERY),
        )
        assertEquals(
            """{"setDeviceStateItemParams":{"key":"dark_ui_mode/dark_ui_activated","itemizationKeys":[],"value":"true","requestInitiatedWhileUnlocked":true}}""",
            AppFunctionCommands.setState("dark_ui_mode/dark_ui_activated", "true")[7],
        )
    }

    @Test
    fun `state summary keeps key name and value while capping large output`() {
        val yaml =
            """
            androidAppfunctionsReturnValue:
              perScreenDeviceStates:
                - description: Dark theme
                  deviceStateItems:
                    - hintText: Always enabled
                      jsonValue: "false"
                      purpose: Whether the system dark theme is manually enabled
                      key: dark_ui_mode/dark_ui_activated
                      name:
                        english: Use dark theme
                        localized: Use dark theme
                    - jsonValue: 30 s
                      purpose: The current screen timeout duration
                      key: display/screen_timeout
            """.trimIndent()

        val summary = AppFunctionOutput.summarizeState(yaml, maxChars = 120, maxItems = 20)

        assertTrue(summary.contains("dark_ui_mode/dark_ui_activated: false (Use dark theme)"))
        assertTrue(summary.contains("output capped"))
        assertFalse(summary.contains("Always enabled"))
        assertTrue(summary.length <= 120)
    }

    @Test
    fun `metadata summary filters writable entries by purpose key and possible values`() {
        val yaml =
            """
            deviceStateItemsMetadata:
              - writable: true
                possibleValues: BOOL
                purpose: Whether the system dark theme is manually enabled
                key: dark_ui_mode/dark_ui_activated
              - writable: false
                possibleValues: STRING
                purpose: Android build number
                key: firmware_version/os_build_number
              - writable: true
                possibleValues: "INTEGER(min=0, max=100)"
                purpose: Current display level
                key: display_settings_screen/brightness
            """.trimIndent()

        val dark = AppFunctionOutput.summarizeMetadata(yaml, "dark")
        val integer = AppFunctionOutput.summarizeMetadata(yaml, "integer")

        assertTrue(dark.contains("dark_ui_mode/dark_ui_activated"))
        assertFalse(dark.contains("os_build_number"))
        assertTrue(integer.contains("display_settings_screen/brightness"))
    }

    @Test
    fun `shell failures distinguish timeout uncertain write identity and command errors`() {
        val timeout = ShellResult("", "", -1, timedOut = true, uid = 2000)
        val wrongIdentity = ShellResult("value", "", 0, timedOut = false, uid = 10000)
        val commandError = ShellResult("", "No valid executor found", 255, timedOut = false, uid = 2000)

        assertTrue(AppFunctionShellMessages.failure(timeout, write = true)!!.message.contains("may have changed"))
        assertEquals(InvocationStatus.FAILED, AppFunctionShellMessages.failure(wrongIdentity, write = false)!!.status)
        assertTrue(AppFunctionShellMessages.failure(commandError, write = false)!!.message.contains("No valid executor"))
        assertEquals(null, AppFunctionShellMessages.failure(ShellResult("ok", "", 0, false, 2000), write = false))
    }

    @Test
    fun `setter response reports the returned current value`() {
        val outcome =
            AppFunctionOutput.summarizeSet(
                """
                androidAppfunctionsReturnValue:
                  isSuccessful: true
                  currentValue: "true"
                """.trimIndent(),
            )

        assertEquals(InvocationStatus.COMPLETED, outcome.status)
        assertEquals("Setting changed. Current value: true.", outcome.message)
    }
}

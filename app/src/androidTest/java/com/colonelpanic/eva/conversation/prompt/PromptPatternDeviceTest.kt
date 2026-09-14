package com.colonelpanic.eva.conversation.prompt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's regex engine is stricter than the desktop JVM's, so a pattern the unit tests accept
 * can still abort the app when its class initializes. Only a device run catches that.
 */
@RunWith(AndroidJUnit4::class)
class PromptPatternDeviceTest {
    @Test
    fun stockPromptAssemblesOnAndroidsRegexEngine() {
        val assembled =
            PromptDefaults.config.validated(PromptDefaults.VARIABLES).assemble(
                PromptContext(voice = true, variables = mapOf("clock" to "It is noon.", "lookup_retries" to "5")),
            )
        assertTrue(assembled.instructions.contains("It is noon."))
        assertTrue(assembled.instructions.isNotBlank())
    }
}

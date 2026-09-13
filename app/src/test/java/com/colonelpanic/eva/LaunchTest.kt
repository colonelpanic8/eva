package com.colonelpanic.eva

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTest {
    @Test
    fun `the launcher waits for the user`() {
        assertEquals(Launch.MANUAL, launchFor("android.intent.action.MAIN", secure = false))
        assertEquals(Launch.MANUAL, launchFor(null, secure = false))
    }

    @Test
    fun `every system voice entry point starts listening`() {
        val actions =
            listOf(
                "android.intent.action.ASSIST",
                "android.intent.action.VOICE_ASSIST",
                "android.intent.action.VOICE_COMMAND",
                "android.speech.action.VOICE_SEARCH_HANDS_FREE",
                "android.speech.action.WEB_SEARCH",
            )
        actions.forEach { action -> assertEquals(action, Launch.HANDS_FREE, launchFor(action, secure = false)) }
    }

    @Test
    fun `a locked device hides the conversation`() {
        val launch = launchFor("android.speech.action.VOICE_SEARCH_HANDS_FREE", secure = true)
        assertEquals(Launch.SECURE_HANDS_FREE, launch)
        assertEquals(true, launch.startsVoice)
        assertEquals(true, launch.locked)
    }
}

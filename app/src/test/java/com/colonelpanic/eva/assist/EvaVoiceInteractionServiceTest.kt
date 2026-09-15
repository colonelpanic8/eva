package com.colonelpanic.eva.assist

import android.app.Application
import android.content.Intent
import android.speech.RecognizerIntent
import com.colonelpanic.eva.Launch
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.launchFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class EvaVoiceInteractionServiceTest {
    @Test
    fun `keyguard invocation launches the activity in secure hands free mode`() {
        val controller = Robolectric.buildService(EvaVoiceInteractionService::class.java).create()
        try {
            val service = controller.get()
            service.onLaunchVoiceAssistFromKeyguard()

            val intent = shadowOf(service).nextStartedActivity
            assertNotNull(intent)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(
                Launch.SECURE_HANDS_FREE,
                launchFor(intent.action, intent.getBooleanExtra(RecognizerIntent.EXTRA_SECURE, false)),
            )
        } finally {
            controller.destroy()
        }
    }
}

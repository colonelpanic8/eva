package com.colonelpanic.eva.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class MessagingSettingsTest {
    @Test fun defaultsAreOffAndApprovedIdentitiesPersist() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("eva.messaging", 0)
            .edit()
            .clear()
            .commit()
        val settings = MessagingSettings(context)
        assertFalse(settings.state.value.enabled)
        assertTrue(
            settings.state.value.replies
                .isEmpty(),
        )
        settings.enable(true)
        settings.allowReply("uid:app:install:signer", true)
        val reopened = MessagingSettings(context)
        assertTrue(reopened.state.value.enabled)
        assertEquals(setOf("uid:app:install:signer"), reopened.state.value.replies)
        assertFalse("uid:app:reinstall:signer" in reopened.state.value.replies)
        reopened.allowReply("uid:app:install:signer", false)
        assertTrue(
            MessagingSettings(context)
                .state.value.replies
                .isEmpty(),
        )
    }
}

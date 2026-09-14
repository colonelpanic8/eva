package com.colonelpanic.eva.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.colonelpanic.eva.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The permission flow must outlive Activity recreation but not the Activity itself. */
@RunWith(AndroidJUnit4::class)
class VoiceAccessRetentionTest {
    @Test
    fun pendingRequestAndDenialSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[VoiceAccessModel::class.java]
                val started = model.update { start("link", microphoneGranted = false) }
                assertEquals(VoiceStart.RequestMicrophone("link"), started)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[VoiceAccessModel::class.java]
                assertNull(model.update { onPermissionResult(granted = false, canAskAgain = true) })
                assertEquals(MicrophoneDenial("link", canAskAgain = true), model.denial)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[VoiceAccessModel::class.java]
                assertEquals(MicrophoneDenial("link", canAskAgain = true), model.denial)
                assertEquals(VoiceStart.Connect("link"), model.update { retry(microphoneGranted = true) })
                assertNull(model.denial)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(ViewModelProvider(activity)[VoiceAccessModel::class.java].denial)
            }
        }
    }
}

package com.colonelpanic.eva.ui

import android.content.pm.ActivityInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.colonelpanic.eva.Launch
import com.colonelpanic.eva.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletRotationTest {
    @Test
    fun tabletRequestsSensorRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expected =
                    if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
                        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                assertEquals(expected, activity.requestedOrientation)
            }
        }
    }

    @Test
    fun handsFreeSurfaceSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[VoiceAccessModel::class.java].surface = Launch.HANDS_FREE
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(Launch.HANDS_FREE, ViewModelProvider(activity)[VoiceAccessModel::class.java].surface)
            }
        }
    }
}

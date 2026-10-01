package com.colonelpanic.eva.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.colonelpanic.eva.web.WebResearchConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebResearchSectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun timeoutDragSavesOnceOnReleaseAndKeepsOtherSettings() {
        val saved = mutableListOf<WebResearchConfiguration>()
        val options = WebResearchConfiguration(model = "custom-model", effort = "medium")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column { WebResearchTimeout(options) { saved += it } }
                }
            }
            val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            slider.performTouchInput {
                down(center)
                moveTo(centerRight + Offset(20f, 0f))
            }
            compose.onNodeWithText("Timeout: 60 seconds").assertExists()
            compose.runOnIdle { assertTrue(saved.isEmpty()) }
            slider.performTouchInput { up() }
            compose.runOnIdle {
                assertEquals(listOf(options.copy(timeoutSeconds = 60)), saved)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}

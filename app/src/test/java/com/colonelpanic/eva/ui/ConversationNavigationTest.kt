package com.colonelpanic.eva.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.colonelpanic.eva.conversation.ThreadSummary
import org.junit.Assert.assertEquals
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
class ConversationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun archiveShowsPastConversationsAndOpensTheSelectedOne() {
        val opened = mutableListOf<String>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    ConversationArchiveScreen(
                        threads =
                            listOf(
                                ThreadSummary("old", "Previous request", 1, true),
                                ThreadSummary("fresh", "New conversation", 2, false),
                            ),
                        onOpenDrawer = {},
                        onShowThread = { opened += it },
                    )
                }
            }
            compose.onNodeWithText("Previous request").assertExists()
            compose.onNodeWithText("Working…").assertExists()
            compose.onNodeWithText("New conversation").performClick()
            compose.runOnIdle { assertEquals(listOf("fresh"), opened) }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun emptyArchiveExplainsThereAreNoPastConversations() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme { ConversationArchiveScreen(emptyList(), {}, {}) }
            }
            compose.onNodeWithText("No past conversations.").assertExists()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}

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

    @Test fun conversationsOpenSelectedHistoryAndCreateNewThreads() {
        val opened = mutableListOf<String>()
        var created = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    ConversationsScreen(
                        threads =
                            listOf(
                                ThreadSummary("old", "Previous request", 1, true),
                                ThreadSummary("fresh", "New conversation", 2, false),
                            ),
                        onOpenDrawer = {},
                        onNewThread = { created++ },
                        onShowThread = { opened += it },
                    )
                }
            }
            compose.onNodeWithText("New").performClick()
            compose.runOnIdle { assertEquals(1, created) }
            compose.onNodeWithText("Previous request").assertExists()
            compose.onNodeWithText("Working…").assertExists()
            compose.onNodeWithText("New conversation").performClick()
            compose.runOnIdle { assertEquals(listOf("fresh"), opened) }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun emptyConversationsExplainsThereAreNoConversations() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme { ConversationsScreen(emptyList(), {}, {}, {}) }
            }
            compose.onNodeWithText("No conversations yet.").assertExists()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}

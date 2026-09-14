package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class NotificationReplyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun action(immutable: Boolean = false): Notification.Action =
        Notification.Action
            .Builder(
                null,
                "Reply",
                PendingIntent.getBroadcast(
                    app,
                    if (immutable) 2 else 1,
                    Intent("test.reply").setPackage(app.packageName),
                    if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE,
                ),
            ).addRemoteInput(RemoteInput.Builder("answer").setAllowFreeFormInput(true).build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()

    @Test fun replyIntentCarriesOnlyTheDeclaredTextResult() {
        val action = action()
        val intent = NotificationReply.intent(action, "Hi & hello")
        assertEquals("Hi & hello", RemoteInput.getResultsFromIntent(intent).getCharSequence("answer"))
        assertEquals(1, RemoteInput.getResultsFromIntent(intent).size())
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(intent))
    }

    @Test fun wrongCreatorAmbiguousActionsAndImmutableIntentsAreRefused() {
        val action = action()
        val notification = Notification.Builder(app, "test").addAction(action).build()
        assertEquals(
            action,
            NotificationReply.action(notification, requireNotNull(action.actionIntent.creatorPackage), action.actionIntent.creatorUid),
        )
        assertNull(NotificationReply.action(notification, "other.app", action.actionIntent.creatorUid))
        assertNull(NotificationReply.action(notification, requireNotNull(action.actionIntent.creatorPackage), -1))
        val ambiguous =
            Notification
                .Builder(app, "test")
                .addAction(action)
                .addAction(action)
                .build()
        assertNull(NotificationReply.action(ambiguous, requireNotNull(action.actionIntent.creatorPackage), action.actionIntent.creatorUid))
        val immutable = action(true)
        assertNull(
            NotificationReply.action(
                Notification.Builder(app, "test").addAction(immutable).build(),
                requireNotNull(immutable.actionIntent.creatorPackage),
                immutable.actionIntent.creatorUid,
            ),
        )
    }
}

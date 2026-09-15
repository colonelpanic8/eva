package com.colonelpanic.eva.adapters.android

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle

internal object NotificationReply {
    fun action(
        notification: Notification,
        packageName: String,
        uid: Int,
    ): Notification.Action? =
        notification.actions
            .orEmpty()
            .filter { action ->
                action.actionIntent?.creatorUid == uid &&
                    action.actionIntent?.creatorPackage == packageName &&
                    action.remoteInputs?.count { it.allowFreeFormInput } == 1 &&
                    isReply(action) && acceptsInput(action)
            }.singleOrNull()

    private fun isReply(action: Notification.Action): Boolean =
        if (Build.VERSION.SDK_INT >= 28) action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY else true

    private fun acceptsInput(action: Notification.Action): Boolean =
        if (Build.VERSION.SDK_INT >= 31) !action.actionIntent.isImmutable else true

    fun intent(
        action: Notification.Action,
        message: String,
    ): Intent {
        val input = action.remoteInputs.single { it.allowFreeFormInput }
        return Intent().also {
            RemoteInput.addResultsToIntent(arrayOf(input), it, Bundle().apply { putCharSequence(input.resultKey, message) })
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(it, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
    }
}

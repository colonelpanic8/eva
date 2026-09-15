package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessagingPreferences(
    val enabled: Boolean = false,
    val replies: Set<String> = emptySet(),
)

class MessagingSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("eva.messaging", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(MessagingPreferences(prefs.getBoolean("enabled", false), prefs.getStringSet("replies", emptySet())!!.toSet()))
    val state = mutable.asStateFlow()

    fun enable(value: Boolean) {
        prefs.edit { putBoolean("enabled", value) }
        mutable.value = mutable.value.copy(enabled = value)
    }

    fun allowReply(
        identity: String,
        value: Boolean,
    ) {
        val replies = if (value) mutable.value.replies + identity else mutable.value.replies - identity
        prefs.edit { putStringSet("replies", replies) }
        mutable.value = mutable.value.copy(replies = replies)
    }
}

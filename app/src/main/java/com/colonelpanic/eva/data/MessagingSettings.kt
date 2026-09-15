package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessagingPreferences(
    val enabled: Boolean = false,
    val replies: Set<String> = emptySet(),
)

class MessagingSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onReplyChanged: (String) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("eva.messaging", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(MessagingPreferences(prefs.getBoolean("enabled", false), prefs.getStringSet("replies", emptySet())!!.toSet()))
    val state = mutable.asStateFlow()

    fun enable(value: Boolean) {
        replace(mutable.value.copy(enabled = value))
    }

    fun allowReply(
        identity: String,
        value: Boolean,
    ) {
        val replies = if (value) mutable.value.replies + identity else mutable.value.replies - identity
        replace(mutable.value.copy(replies = replies))
        onReplyChanged(identity)
    }

    fun replace(value: MessagingPreferences) {
        commit {
            putBoolean("enabled", value.enabled)
            putStringSet("replies", value.replies)
        }
        mutable.value = value.copy(replies = value.replies.toSet())
        onChanged()
    }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save messaging settings." }
    }
}

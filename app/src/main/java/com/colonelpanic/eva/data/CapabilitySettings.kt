package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which optional capabilities the model is allowed to see. Separate from [OpenAiSettings], which
 * owns credentials, and from [AppearanceSettings], which owns looks. Turning one off removes it
 * from the catalog EVA sends, so the model is never told about an ability it must not use.
 */
class CapabilitySettings(
    context: Context,
    private val onChanged: () -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableScreenControl = MutableStateFlow(prefs.getBoolean(SCREEN_CONTROL, true))

    val screenControlFlow = mutableScreenControl.asStateFlow()

    val screenControlEnabled: Boolean get() = mutableScreenControl.value

    fun saveScreenControl(value: Boolean) {
        commit { putBoolean(SCREEN_CONTROL, value) }
        mutableScreenControl.value = value
        onChanged()
    }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save capability settings." }
    }

    private companion object {
        const val SCREEN_CONTROL = "capabilities.screenControl"
    }
}

package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which optional capabilities the model is allowed to see. Separate from [OpenAiSettings], which
 * owns credentials, and from [AppearanceSettings], which owns looks. Turning one off removes it
 * from the catalog EVA sends, so the model is never told about an ability it must not use.
 */
class CapabilitySettings(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableScreenControl = MutableStateFlow(prefs.getBoolean(SCREEN_CONTROL, true))

    val screenControlFlow = mutableScreenControl.asStateFlow()

    val screenControlEnabled: Boolean get() = mutableScreenControl.value

    fun saveScreenControl(value: Boolean) {
        prefs.edit { putBoolean(SCREEN_CONTROL, value) }
        mutableScreenControl.value = value
    }

    private companion object {
        const val SCREEN_CONTROL = "capabilities.screenControl"
    }
}

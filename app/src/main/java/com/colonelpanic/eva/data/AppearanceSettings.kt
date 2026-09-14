package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How EVA should look. Separate from [OpenAiSettings], which owns credentials.
 * EVA's own palette is the default so the app is recognisably itself on first launch;
 * turning wallpaper colors on hands theming to the system instead.
 */
class AppearanceSettings(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableDynamicColor = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, false))

    val dynamicColorFlow = mutableDynamicColor.asStateFlow()

    fun saveDynamicColor(value: Boolean) {
        prefs.edit { putBoolean(DYNAMIC_COLOR, value) }
        mutableDynamicColor.value = value
    }

    private companion object {
        const val DYNAMIC_COLOR = "appearance.dynamicColor"
    }
}

package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How EVA should look. Separate from [OpenAiSettings], which owns credentials.
 * EVA's own palette is the default so the app is recognisably itself on first launch;
 * turning wallpaper colors on hands theming to the system instead.
 */
class AppearanceSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableDynamicColor = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, false))

    val dynamicColorFlow = mutableDynamicColor.asStateFlow()
    val dynamicColor: Boolean get() = mutableDynamicColor.value

    fun saveDynamicColor(value: Boolean) {
        commit { putBoolean(DYNAMIC_COLOR, value) }
        mutableDynamicColor.value = value
        onChanged()
    }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save appearance settings." }
    }

    private companion object {
        const val DYNAMIC_COLOR = "appearance.dynamicColor"
    }
}

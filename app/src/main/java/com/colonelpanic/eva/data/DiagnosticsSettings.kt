package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much EVA records about its own lifecycle; part of the portable configuration. */
class DiagnosticsSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableVerboseLogging = MutableStateFlow(prefs.getBoolean(VERBOSE_LOGGING, false))

    val verboseLoggingFlow = mutableVerboseLogging.asStateFlow()
    val verboseLogging: Boolean get() = mutableVerboseLogging.value

    fun saveVerboseLogging(value: Boolean) {
        commit { putBoolean(VERBOSE_LOGGING, value) }
        mutableVerboseLogging.value = value
        onChanged()
    }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save diagnostics settings." }
    }

    private companion object {
        const val VERBOSE_LOGGING = "diagnostics.verboseLogging"
    }
}

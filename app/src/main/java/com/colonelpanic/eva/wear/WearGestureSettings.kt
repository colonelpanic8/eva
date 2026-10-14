package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WearGestureSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            prefs.getString(KEY, null)?.let {
                WearProtocol.decode<WearGestureConfiguration>(it.toByteArray())
            } ?: WearGestureConfiguration(),
        )
    val state = mutable.asStateFlow()
    val value: WearGestureConfiguration get() = state.value

    @SuppressLint("UseKtx")
    fun save(value: WearGestureConfiguration) {
        if (value == this.value) return
        check(prefs.edit().putString(KEY, WearProtocol.encode(value).toString(Charsets.UTF_8)).commit()) {
            "Could not save watch gesture settings."
        }
        mutable.value = value
        onChanged()
    }

    private companion object {
        const val KEY = "wearGesture"
    }
}

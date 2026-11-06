package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.capability.ConfirmLevel
import com.colonelpanic.eva.capability.ToolPreferenceStore
import com.colonelpanic.eva.capability.ToolPreferences
import com.colonelpanic.eva.data.configuration.ToolPatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The `tools` configuration group: per-capability confirmation level and default arguments. */
class ToolSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
) : ToolPreferenceStore {
    private val prefs = context.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap(),
        )
    val state = mutable.asStateFlow()
    val value: Map<String, ToolPatch> get() = state.value

    @Volatile private var preferences: Map<String, ToolPreferences> = value.toPreferences()

    override fun preferences(): Map<String, ToolPreferences> = preferences

    override fun save(
        capabilityId: String,
        preferences: ToolPreferences?,
    ) = save(
        if (preferences == null) {
            value - capabilityId
        } else {
            value + (capabilityId to ToolPatch(preferences.confirm?.wire, preferences.defaults.takeIf { it.isNotEmpty() }))
        },
    )

    @SuppressLint("UseKtx")
    fun save(value: Map<String, ToolPatch>) {
        if (value == this.value) return
        val editor = prefs.edit()
        if (value.isEmpty()) editor.remove(KEY) else editor.putString(KEY, json.encodeToString(serializer, value))
        check(editor.commit()) { "Could not save tool settings." }
        preferences = value.toPreferences()
        mutable.value = value
        onChanged()
    }

    private companion object {
        const val KEY = "tools"
        val json = Json { ignoreUnknownKeys = false }
        val serializer = MapSerializer(String.serializer(), ToolPatch.serializer())

        fun Map<String, ToolPatch>.toPreferences() =
            mapValues { (_, tool) -> ToolPreferences(tool.confirm?.let(ConfirmLevel::of), tool.defaults.orEmpty()) }
    }
}

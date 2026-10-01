package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.web.WebResearchConfiguration
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
    private val onCredentialChanged: (String) -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableScreenControl = MutableStateFlow(prefs.getBoolean(SCREEN_CONTROL, true))

    private val secrets = SecretStore(context)
    private val mutableDeviceTask =
        MutableStateFlow(
            runCatching {
                kotlinx.serialization.json.Json.decodeFromString<com.colonelpanic.eva.data.configuration.DeviceTaskConfiguration>(
                    prefs.getString("capabilities.deviceTask", "{}")!!,
                )
            }.getOrDefault(
                com.colonelpanic.eva.data.configuration
                    .DeviceTaskConfiguration(),
            ),
        )
    val deviceTaskFlow = mutableDeviceTask.asStateFlow()
    val deviceTask get() = mutableDeviceTask.value

    fun saveDeviceTask(value: com.colonelpanic.eva.data.configuration.DeviceTaskConfiguration) {
        commit {
            putString(
                "capabilities.deviceTask",
                kotlinx.serialization.json.Json.encodeToString(
                    com.colonelpanic.eva.data.configuration.DeviceTaskConfiguration
                        .serializer(),
                    value,
                ),
            )
        }
        mutableDeviceTask.value = value
        onChanged()
    }

    fun portalToken(): String? = secrets.read("device/portal")

    fun savePortalToken(value: String) {
        if (value.isBlank()) secrets.clear("device/portal") else secrets.write("device/portal", value.trim())
        onCredentialChanged("device/portal")
    }

    private val mutableWebResearch =
        MutableStateFlow(
            runCatching {
                kotlinx.serialization.json.Json
                    .decodeFromString<WebResearchConfiguration>(prefs.getString("capabilities.webResearch", "{}")!!)
            }.getOrDefault(WebResearchConfiguration()),
        )
    val webResearchFlow = mutableWebResearch.asStateFlow()
    val webResearch get() = mutableWebResearch.value

    fun saveWebResearch(value: WebResearchConfiguration) {
        commit {
            putString(
                "capabilities.webResearch",
                kotlinx.serialization.json.Json
                    .encodeToString(WebResearchConfiguration.serializer(), value),
            )
        }
        mutableWebResearch.value = value
        onChanged()
    }

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

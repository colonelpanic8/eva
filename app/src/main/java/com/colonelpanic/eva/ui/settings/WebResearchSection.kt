package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.colonelpanic.eva.ui.ModelPicker
import com.colonelpanic.eva.ui.ReasoningEffortPicker
import com.colonelpanic.eva.web.WebResearchConfiguration
import kotlin.math.roundToInt

@Composable
internal fun WebResearchSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val options = state.webResearch
    SettingsSection("Web research") {
        SettingsSwitchRow(
            title = "Research without opening the browser",
            supporting = "Uses ChatGPT sign-in when available. With an API key, each research call is billed for a search fee plus tokens.",
            checked = options.enabled,
            onCheckedChange = { actions.onWebResearchChange(options.copy(enabled = it)) },
        )
        SettingsBlock {
            state.webResearchNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ModelPicker("Research model", options.model, state.availableTextModels, "gpt-6-sol", {
                val model = it.ifBlank { WebResearchConfiguration().model }
                runCatching { options.copy(model = model) }.getOrNull()?.let(actions.onWebResearchChange)
            })
            ReasoningEffortPicker("Research reasoning effort", options.effort, WebResearchConfiguration.EFFORTS, {
                actions.onWebResearchChange(options.copy(effort = it))
            })
            WebResearchTimeout(options, actions.onWebResearchChange)
        }
    }
}

@Composable
internal fun WebResearchTimeout(
    options: WebResearchConfiguration,
    onChange: (WebResearchConfiguration) -> Unit,
) {
    var timeout by remember(options.timeoutSeconds) { mutableFloatStateOf(options.timeoutSeconds.toFloat()) }
    Text("Timeout: ${timeout.roundToInt()} seconds")
    Slider(
        value = timeout,
        onValueChange = { timeout = it },
        onValueChangeFinished = { onChange(options.copy(timeoutSeconds = timeout.roundToInt())) },
        valueRange = 5f..60f,
        steps = 54,
    )
}

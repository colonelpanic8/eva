package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            supporting = "Uses ChatGPT sign-in when available; otherwise uses your separately billed OpenAI API key.",
            checked = options.enabled,
            onCheckedChange = { actions.onWebResearchChange(options.copy(enabled = it)) },
        )
        SettingsBlock {
            ModelPicker("Research model", options.model, state.availableTextModels, "gpt-6-sol", {
                val model = it.ifBlank { WebResearchConfiguration().model }
                runCatching { options.copy(model = model) }.getOrNull()?.let(actions.onWebResearchChange)
            })
            ReasoningEffortPicker("Research reasoning effort", options.effort, WebResearchConfiguration.EFFORTS, {
                actions.onWebResearchChange(options.copy(effort = it))
            })
            Text("Timeout: ${options.timeoutSeconds} seconds")
            Slider(
                value = options.timeoutSeconds.toFloat(),
                onValueChange = { actions.onWebResearchChange(options.copy(timeoutSeconds = it.roundToInt())) },
                valueRange = 5f..60f,
                steps = 54,
            )
        }
    }
}

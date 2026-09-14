package com.colonelpanic.eva.ui.settings

import com.colonelpanic.eva.capability.extensions.ExtensionSettings
import com.colonelpanic.eva.providers.openai.OpenAiModels
import com.colonelpanic.eva.providers.openai.SignInState

/** Everything the settings screen renders, collected once by the activity. */
data class SettingsUiState(
    val extensions: ExtensionSettings = ExtensionSettings(),
    val account: String? = null,
    val signIn: SignInState = SignInState.Idle,
    val hasApiKey: Boolean = false,
    val hasHostLink: Boolean = false,
    val textModel: String = "",
    val realtimeModel: String = "",
    val availableTextModels: List<String> = emptyList(),
    val availableRealtimeModels: List<String> = emptyList(),
    val reasoningEffort: String = OpenAiModels.REASONING_EFFORT,
    val voiceLookupRetries: Int = 5,
    val isDeviceAssistant: Boolean = false,
    val canSeeMediaSessions: Boolean = false,
    val canControlScreen: Boolean = false,
    val screenControlEnabled: Boolean = true,
    val dynamicColor: Boolean = false,
) {
    /** Whether any of the three ways to reach a provider is configured. */
    val hasCredential: Boolean get() = account != null || hasApiKey || hasHostLink
}

/**
 * Saving a secret can be rejected, so those callbacks answer with the reason to show
 * and null when the value was stored. Silently discarding a bad paste is how the old
 * connection panel behaved, and it left nothing on screen to explain the failure.
 */
data class SettingsActions(
    val onExtensionEnable: (String, Boolean) -> Unit = { _, _ -> },
    val onExtensionMutation: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onRefreshExtensions: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onCancelSignIn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onSaveApiKey: (String) -> String? = { null },
    val onClearApiKey: () -> Unit = {},
    val onSaveHostLink: (String) -> String? = { null },
    val onClearHostLink: () -> Unit = {},
    val onSelectTextModel: (String) -> Unit = {},
    val onSelectRealtimeModel: (String) -> Unit = {},
    val onSelectReasoningEffort: (String) -> Unit = {},
    val onVoiceLookupRetriesChange: (Int) -> Unit = {},
    val onOpenAssistantSettings: () -> Unit = {},
    val onOpenMediaControlSettings: () -> Unit = {},
    val onScreenControlChange: (Boolean) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
)

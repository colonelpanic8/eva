package com.colonelpanic.eva.ui.settings

import com.colonelpanic.eva.capability.extensions.ExtensionSettings
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.data.MessagingPreferences
import com.colonelpanic.eva.data.configuration.ConfigurationStatus
import com.colonelpanic.eva.messaging.MessagingApp
import com.colonelpanic.eva.providers.openai.OpenAiModels
import com.colonelpanic.eva.providers.openai.SignInState
import com.colonelpanic.eva.providers.spotify.SpotifyConnectState

/** One runtime grant the messaging screen reports. */
data class PermissionStatus(
    val label: String,
    val granted: Boolean,
)

/** Everything the settings screens render, collected once by the activity. */
data class SettingsUiState(
    val configuration: ConfigurationStatus = ConfigurationStatus(),
    val messaging: MessagingPreferences =
        MessagingPreferences(),
    val messagingApps: List<MessagingApp> = emptyList(),
    val messagingPermissions: List<PermissionStatus> = emptyList(),
    val rememberedNumbers: Int = 0,
    val memories: com.colonelpanic.eva.data.Memories =
        com.colonelpanic.eva.data
            .Memories(),
    val plugins: com.colonelpanic.eva.adapters.declarative.PluginBrowserState =
        com.colonelpanic.eva.adapters.declarative
            .PluginBrowserState(),
    val packages: List<com.colonelpanic.eva.data.PackageConfigurationEntry> = emptyList(),
    val contentProviders: Map<String, com.colonelpanic.eva.adapters.android.ContentProviderAccess> = emptyMap(),
    val waitDefaults: Map<com.colonelpanic.eva.capability.InteractionMode, Long> = emptyMap(),
    val extensions: ExtensionSettings = ExtensionSettings(),
    val extensionOverflow: Map<String, String> = emptyMap(),
    /** Extensions the user has told a refresh not to enable on its own; absent means it may. */
    val autoEnabled: Map<String, Boolean> = emptyMap(),
    val account: String? = null,
    val signIn: SignInState = SignInState.Idle,
    val hasApiKey: Boolean = false,
    val hasHostLink: Boolean = false,
    val textModel: String = "",
    val realtimeModel: String = "",
    val availableTextModels: List<String> = emptyList(),
    val availableRealtimeModels: List<String> = emptyList(),
    val reasoningEffort: String = OpenAiModels.TEXT_REASONING_EFFORT,
    val voiceReasoningEffort: String = OpenAiModels.VOICE_REASONING_EFFORT,
    val voiceLookupRetries: Int = 5,
    /** Read from the prompt's call slot, which this switch edits. */
    val callMode: VoiceCallMode? = VoiceCallMode.ONE_REQUEST,
    val isDeviceAssistant: Boolean = false,
    val canSeeMediaSessions: Boolean = false,
    val canControlScreen: Boolean = false,
    val screenControlEnabled: Boolean = true,
    val spotifyClientId: String? = null,
    val spotifyAccount: String? = null,
    val spotifyPremium: Boolean? = null,
    val spotifyConnect: SpotifyConnectState = SpotifyConnectState.Idle,
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
    val onSelectConfigurationFolder: () -> Unit = {},
    val onReloadConfiguration: () -> Unit = {},
    val onGitEnabled: (Boolean) -> Unit = {},
    val onSaveGit: (String, String, String, String, String, String) -> String? = { _, _, _, _, _, _ -> null },
    val onClearGitToken: () -> Unit = {},
    val onMessagingEnable: (Boolean) -> Unit = {},
    val onMessagingReply: (String, Boolean) -> Unit = { _, _ -> },
    val onMessagingRefresh: () -> Unit = {},
    val onForgetRememberedNumbers: () -> Unit = {},
    val onKeepMemory: (String) -> Unit = {},
    val onForgetMemory: (String) -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onRepositoryRefresh: () -> Unit = {},
    val onRepositoryRefreshNew: () -> Unit = {},
    val onRepositorySync: (String) -> Unit = {},
    val onRepositoryAdd: (String) -> Unit = {},
    val onRepositoryRemove: (String) -> Unit = {},
    val onExtensionAutoEnable: (String, Boolean) -> Unit = { _, _ -> },
    val onPluginFileImport: () -> Unit = {},
    val onPluginUrlPreview: (String) -> Unit = {},
    val onPluginInstall: () -> Unit = {},
    val onPluginRemove: (String) -> Unit = {},
    val onSavePackageServer: (String, String, String, String, String, String) -> String? = { _, _, _, _, _, _ -> null },
    val onClearPackageServer: (String, String) -> Unit = { _, _ -> },
    val onSaveWait: (String, String) -> String? = { _, _ -> null },
    val onExtensionEnable: (String, Boolean) -> Unit = { _, _ -> },
    val onExtensionEnableAll: (String) -> Unit = {},
    val onExtensionMutation: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onContentPermission: (String) -> Unit = {},
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
    val onSelectVoiceReasoningEffort: (String) -> Unit = {},
    val onVoiceLookupRetriesChange: (Int) -> Unit = {},
    val onEndAfterOneRequestChange: (Boolean) -> Unit = {},
    val onOpenAssistantSettings: () -> Unit = {},
    val onOpenMediaControlSettings: () -> Unit = {},
    val onScreenControlChange: (Boolean) -> Unit = {},
    val onSaveSpotifyClientId: (String) -> String? = { null },
    val onConnectSpotify: () -> String? = { null },
    val onCancelSpotifyConnect: () -> Unit = {},
    val onDisconnectSpotify: () -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
)

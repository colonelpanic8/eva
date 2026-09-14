package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.R
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.ui.theme.EvaTheme

/**
 * What a headset launch shows on a locked device: transport only. The conversation stays
 * hidden because whoever is holding the phone has not unlocked it.
 */
@Composable
fun HandsFreeSurface(
    state: ConversationState,
    onStop: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Locked, so the conversation stays hidden. Unlock to read it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VoiceControls(
                state = state.mediaState,
                controls = state.mediaControls,
                enabled = true,
                onStart = {},
                onStop = onStop,
                onToggleMicrophone = onToggleMicrophone,
                onTogglePlayback = onTogglePlayback,
            )
        }
    }
}

@Preview
@Composable
private fun HandsFreeSurfacePreview() {
    EvaTheme { HandsFreeSurface(state = ConversationState(isLoading = false), onStop = {}, onToggleMicrophone = {}, onTogglePlayback = {}) }
}

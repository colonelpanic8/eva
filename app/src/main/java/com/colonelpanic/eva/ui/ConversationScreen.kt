package com.colonelpanic.eva.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus

/**
 * The conversation, and almost nothing else. Configuration lives in settings, a live
 * session lives in the docked bar, and what is left of connecting is one row above the
 * composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    state: ConversationState,
    hasCredential: Boolean,
    onSubmit: (String) -> Unit,
    onConnect: () -> Unit,
    onVoice: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTogglePlayback: () -> Unit,
    denial: MicrophoneDenial?,
    onRetryMicrophone: () -> Unit,
    onDismissDenial: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val storageFailed = state.errorMessage != null
    val canSend =
        draft.isNotBlank() && !state.isLoading && !state.isSubmitting && !storageFailed &&
            state.providerStatus == ProviderStatus.CONNECTED &&
            !state.voiceMode
    val listState = rememberLazyListState()
    val inSession = state.voiceMode && state.providerStatus != ProviderStatus.DISCONNECTED

    LaunchedEffect(state.entries.lastOrNull()?.id) {
        if (state.entries.isNotEmpty()) listState.scrollToItem(0)
    }

    fun send() {
        if (!canSend) return
        onSubmit(draft.trim())
        draft = ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EVA", style = MaterialTheme.typography.titleLarge)
                        Text(text = state.providerLabel, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = inSession,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    VoiceSessionBar(
                        state = state,
                        onDisconnect = onDisconnect,
                        onToggleMicrophone = onToggleMicrophone,
                        onTogglePlayback = onTogglePlayback,
                    )
                }
                state.providerMessage?.let { ProviderMessage(it) }
                if (!inSession) {
                    ConnectBar(
                        state = state,
                        hasCredential = hasCredential,
                        onConnect = onConnect,
                        onVoice = onVoice,
                        onDisconnect = onDisconnect,
                        denial = denial,
                        onRetryMicrophone = onRetryMicrophone,
                        onDismissDenial = onDismissDenial,
                    )
                }
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    enabled = !storageFailed,
                    canSend = canSend,
                    supportingText = composerHint(state),
                    onSend = ::send,
                )
            }
        },
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingHistory(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                state.errorMessage?.let { message ->
                    item(key = "storage-error") { StorageErrorBanner(message) }
                }
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        EmptyConversation(onSampleSelected = { draft = it }, enabled = !storageFailed)
                    }
                }
                items(state.entries.asReversed(), key = { it.id }) { entry ->
                    ConversationEntryItem(entry)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    supportingText: String,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                    .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                singleLine = true,
                placeholder = { Text("Ask EVA…") },
                supportingText = { Text(supportingText) },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Send,
                    ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Request to EVA" },
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun LoadingHistory(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).semantics { contentDescription = "Loading" })
            Text(
                text = "Loading your action history…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

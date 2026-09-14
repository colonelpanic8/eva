package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDrawer: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selected) {
                listOf("Installed", "Browse", "Settings").forEachIndexed { index, title ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                }
            }
            key(selected) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (selected) {
                        0 -> {
                            ExtensionsSection(state.extensions, actions, state.extensionOverflow)
                            RepositoryInstallationsSection(state.plugins, actions)
                        }

                        1 -> {
                            PluginRepositorySection(state.plugins, actions)
                        }

                        else -> {
                            PackageConfigurationSection(state, actions)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExtensionsPreview() {
    EvaTheme(dynamicColor = false) {
        ExtensionsScreen(SettingsUiState(), SettingsActions(), {})
    }
}

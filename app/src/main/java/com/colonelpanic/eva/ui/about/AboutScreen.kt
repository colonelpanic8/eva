package com.colonelpanic.eva.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.R
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.settings.SettingsBlock
import com.colonelpanic.eva.ui.settings.SettingsDivider
import com.colonelpanic.eva.ui.settings.SettingsRow
import com.colonelpanic.eva.ui.settings.SettingsSection
import com.colonelpanic.eva.ui.theme.EvaTheme

private const val PROJECT_URL = "https://github.com/colonelpanic8/eva"
private const val ISSUES_URL = "$PROJECT_URL/issues"
private const val LICENSE_URL = "$PROJECT_URL/blob/main/LICENSE"
private const val FDROID_URL = "https://colonelpanic8.github.io/eva/"
private const val DESCRIPTION =
    "An open, extensible Android assistant. Speak or type a request; EVA picks from the phone " +
        "actions it knows and journals what it dispatched."

/**
 * What a bug report needs and what the project is. The build shown here is the installed
 * one rather than a compiled-in constant, so a debug install cannot claim a release version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    info: AboutInfo,
    onOpenDrawer: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsBlock(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = DESCRIPTION,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsDivider()
            SettingsSection("Build") {
                SettingsRow(title = "Version", supporting = info.versionLabel)
                SettingsRow(title = "Application ID", supporting = info.applicationId)
            }
            SettingsDivider()
            SettingsSection("Project") {
                SettingsRow(
                    title = "Source code",
                    supporting = PROJECT_URL,
                    onClick = { onOpenUrl(PROJECT_URL) },
                )
                SettingsRow(
                    title = "Report an issue",
                    supporting = ISSUES_URL,
                    onClick = { onOpenUrl(ISSUES_URL) },
                )
                SettingsRow(
                    title = "Updates",
                    supporting = "$FDROID_URL · the project's own F-Droid repository",
                    onClick = { onOpenUrl(FDROID_URL) },
                )
                SettingsRow(
                    title = "License",
                    supporting = "Apache License 2.0",
                    onClick = { onOpenUrl(LICENSE_URL) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(name = "About", showBackground = true)
@Preview(name = "About dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutPreview() {
    EvaTheme(dynamicColor = false) {
        AboutScreen(
            info = AboutInfo(version = "0.11.0", versionCode = 11_000, applicationId = "com.colonelpanic.eva"),
            onOpenDrawer = {},
            onOpenUrl = {},
        )
    }
}

package com.colonelpanic.eva.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = Blue40,
        onPrimary = Neutral99,
        primaryContainer = Blue90,
        onPrimaryContainer = Blue10,
        secondary = Teal40,
        onSecondary = Neutral99,
        secondaryContainer = Teal90,
        onSecondaryContainer = Teal10,
        tertiary = Mint40,
        onTertiary = Neutral99,
        tertiaryContainer = Mint90,
        onTertiaryContainer = Mint10,
        error = Red40,
        onError = Neutral99,
        errorContainer = Red90,
        onErrorContainer = Red10,
        background = Neutral99,
        onBackground = Neutral10,
        surface = Neutral99,
        onSurface = Neutral10,
        surfaceVariant = Neutral90,
        onSurfaceVariant = Neutral30,
        surfaceContainerLowest = Neutral100,
        surfaceContainerLow = Neutral98,
        surfaceContainer = Neutral96,
        surfaceContainerHigh = Neutral94,
        surfaceContainerHighest = Neutral92,
        outline = Neutral50,
        outlineVariant = Neutral80,
    )

private val DarkColors =
    darkColorScheme(
        primary = Blue80,
        onPrimary = Blue20,
        primaryContainer = Blue30,
        onPrimaryContainer = Blue90,
        secondary = Teal80,
        onSecondary = Teal20,
        secondaryContainer = Teal30,
        onSecondaryContainer = Teal90,
        tertiary = Mint80,
        onTertiary = Mint20,
        tertiaryContainer = Mint30,
        onTertiaryContainer = Mint90,
        error = Red80,
        onError = Red20,
        errorContainer = Red30,
        onErrorContainer = Red90,
        background = Neutral10,
        onBackground = NeutralOnDark,
        surface = Neutral10,
        onSurface = NeutralOnDark,
        surfaceVariant = Neutral30,
        onSurfaceVariant = Neutral80,
        surfaceContainerLowest = Neutral6,
        surfaceContainerLow = Neutral12,
        surfaceContainer = Neutral17,
        surfaceContainerHigh = Neutral22,
        surfaceContainerHighest = Neutral24,
        outline = Neutral60,
        outlineVariant = Neutral30,
    )

@Composable
fun EvaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EvaTypography,
        shapes = EvaShapes,
        content = content,
    )
}

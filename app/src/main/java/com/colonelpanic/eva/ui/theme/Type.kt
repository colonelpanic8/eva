package com.colonelpanic.eva.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * The Material scale with three deliberate changes: titles and headlines carry more
 * weight so a screen's subject reads before its body, and [Typography.labelLarge]
 * gains weight because status lines lean on it throughout the conversation.
 */
private val base = Typography()

internal val EvaTypography =
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )

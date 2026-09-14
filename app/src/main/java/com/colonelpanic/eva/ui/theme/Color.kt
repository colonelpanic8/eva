package com.colonelpanic.eva.ui.theme

import androidx.compose.ui.graphics.Color

// Tonal families read off the EVA mark. The face's blue (#527FA8, which sits between
// Blue40 and Blue80 below) leads as primary, and the
// hair's deep teal (#174A4A) and pale mint (#B9D8CC) follow as secondary and tertiary.
// Every role the app actually draws is named here, because a partially specified scheme
// falls back to Material's baseline purple for whatever it omits.

// Blue — primary
internal val Blue10 = Color(0xFF001E31)
internal val Blue20 = Color(0xFF003353)
internal val Blue30 = Color(0xFF0E4A6F)
internal val Blue40 = Color(0xFF30638C)
internal val Blue80 = Color(0xFFA2CBEA)
internal val Blue90 = Color(0xFFCCE5FF)

// Teal — secondary
internal val Teal10 = Color(0xFF00201D)
internal val Teal20 = Color(0xFF0A3733)
internal val Teal30 = Color(0xFF174A4A)
internal val Teal40 = Color(0xFF2F615C)
internal val Teal80 = Color(0xFF6FAFA3)
internal val Teal90 = Color(0xFFB9D8CC)

// Mint — tertiary, which the conversation uses for in-progress status
internal val Mint10 = Color(0xFF0A2016)
internal val Mint20 = Color(0xFF17372A)
internal val Mint30 = Color(0xFF2D4E3F)
internal val Mint40 = Color(0xFF446656)
internal val Mint80 = Color(0xFFAACFBB)
internal val Mint90 = Color(0xFFC6EBD6)

// Neutrals, tinted toward the primary so surfaces sit under a blue app bar without clashing
internal val Neutral100 = Color(0xFFFFFFFF)
internal val Neutral99 = Color(0xFFFAFCFF)
internal val Neutral98 = Color(0xFFF1F4F9)
internal val Neutral96 = Color(0xFFEBEEF3)
internal val Neutral94 = Color(0xFFE5E8ED)
internal val Neutral92 = Color(0xFFDFE2E8)
internal val Neutral90 = Color(0xFFDDE3EA)
internal val Neutral80 = Color(0xFFC1C7CE)
internal val Neutral60 = Color(0xFF8B9198)
internal val Neutral50 = Color(0xFF71787E)
internal val Neutral30 = Color(0xFF41474D)
internal val Neutral24 = Color(0xFF31353A)
internal val Neutral22 = Color(0xFF272B2F)
internal val Neutral17 = Color(0xFF1C2024)
internal val Neutral12 = Color(0xFF181C1F)
internal val Neutral10 = Color(0xFF101417)
internal val Neutral6 = Color(0xFF0B0F12)
internal val NeutralOnDark = Color(0xFFDFE3E7)

// Error, stated explicitly so it is part of the same system rather than inherited
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

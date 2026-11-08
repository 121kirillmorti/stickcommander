package com.stickcommander.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette derived from the M5StickC's orange enclosure.
val BrandOrange = Color(0xFFB35410)
val BrandOrangeDark = Color(0xFFFFB784)
val BrandBlue = Color(0xFF3C637B)
val BrandBlueDark = Color(0xFFA6CCE6)

val LightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFFB35410),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC6),
    onPrimaryContainer = Color(0xFF3B1600),
    secondary = Color(0xFF6F5A4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADDC8),
    onSecondaryContainer = Color(0xFF27190E),
    tertiary = Color(0xFF3C637B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FF),
    onTertiaryContainer = Color(0xFF001E2D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF211A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF211A16),
    surfaceVariant = Color(0xFFF2DFD3),
    onSurfaceVariant = Color(0xFF51443B),
    outline = Color(0xFF83746A),
    outlineVariant = Color(0xFFD5C2B6),
    inverseSurface = Color(0xFF362F2A),
    inverseOnSurface = Color(0xFFFBEEE6),
    inversePrimary = BrandOrangeDark,
)

val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = BrandOrangeDark,
    onPrimary = Color(0xFF5B2600),
    primaryContainer = Color(0xFF7E3A00),
    onPrimaryContainer = Color(0xFFFFDCC6),
    secondary = Color(0xFFDFC2AE),
    onSecondary = Color(0xFF3E2D20),
    secondaryContainer = Color(0xFF564335),
    onSecondaryContainer = Color(0xFFFADDC8),
    tertiary = BrandBlueDark,
    onTertiary = Color(0xFF04344A),
    tertiaryContainer = Color(0xFF234B62),
    onTertiaryContainer = Color(0xFFC3E8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120D),
    onBackground = Color(0xFFEFE0D6),
    surface = Color(0xFF19120D),
    onSurface = Color(0xFFEFE0D6),
    surfaceVariant = Color(0xFF51443B),
    onSurfaceVariant = Color(0xFFD5C2B6),
    outline = Color(0xFF9C8D82),
    outlineVariant = Color(0xFF51443B),
    inverseSurface = Color(0xFFEFE0D6),
    inverseOnSurface = Color(0xFF362F2A),
    inversePrimary = BrandOrange,
)

// Terminal accent colors that stay legible on both light and dark surfaces.
val TerminalInfo = Color(0xFF2E7D32)
val TerminalWarning = Color(0xFFB26A00)
val TerminalError = Color(0xFFC62828)
val TerminalBeacon = Color(0xFF00695C)
val TerminalRaw = Color(0xFF6B6B6B)

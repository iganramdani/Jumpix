package com.jumpropeai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Strict Two-Tone Modern Palette: Deep Midnight (#0D1321) + Ice Mint (#2EE59D)
val DeepMidnight = Color(0xFF0D1321)
val MidnightSurface = Color(0xFF151D2E)
val MidnightElevated = Color(0xFF1B253A)
val MidnightBorder = Color(0xFF23304A)
val MidnightGlass = Color(0xD90D1321)

val IceMint = Color(0xFF2EE59D)
val IceMintSoft = Color(0x262EE59D)
val IceMintGlow = Color(0x4D2EE59D)

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF8B9AB5)
val DangerSoft = Color(0xFFFF5C72)

// Backwards-compatible aliases mapped strictly to the Two-Tone Midnight + Ice Mint system
val NeonLime = IceMint
val ElectricCyan = IceMint
val FlameOrange = IceMint
val MintGreen = IceMint
val SoftRed = DangerSoft
val DarkSurface = DeepMidnight
val GlassDark = MidnightGlass
val GlassCard = MidnightSurface
val GlassBorder = MidnightBorder

private val JumpRopeDarkColorScheme = darkColorScheme(
    primary = IceMint,
    onPrimary = DeepMidnight,
    primaryContainer = IceMintSoft,
    onPrimaryContainer = IceMint,
    secondary = IceMint,
    onSecondary = DeepMidnight,
    tertiary = TextSecondary,
    onTertiary = DeepMidnight,
    background = DeepMidnight,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightElevated,
    onSurfaceVariant = TextSecondary,
    outline = MidnightBorder,
    error = DangerSoft,
    onError = Color.White
)

@Composable
fun JumpRopeAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JumpRopeDarkColorScheme,
        content = content
    )
}

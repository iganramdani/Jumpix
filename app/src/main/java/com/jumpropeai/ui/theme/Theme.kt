package com.jumpropeai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonLime = Color(0xFFC6FF00)
val ElectricCyan = Color(0xFF00E5FF)
val FlameOrange = Color(0xFFFF6D00)
val MintGreen = Color(0xFF00E676)
val SoftRed = Color(0xFFFF5252)
val DarkSurface = Color(0xFF0E1116)
val GlassDark = Color(0xB80E1116)
val GlassCard = Color(0x99161B22)
val GlassBorder = Color(0x33FFFFFF)

private val JumpRopeDarkColorScheme = darkColorScheme(
    primary = NeonLime,
    onPrimary = Color(0xFF0A0D10),
    secondary = ElectricCyan,
    onSecondary = Color(0xFF0A0D10),
    tertiary = FlameOrange,
    onTertiary = Color.White,
    background = DarkSurface,
    onBackground = Color.White,
    surface = Color(0xFF161B22),
    onSurface = Color.White,
    error = SoftRed
)

@Composable
fun JumpRopeAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JumpRopeDarkColorScheme,
        content = content
    )
}

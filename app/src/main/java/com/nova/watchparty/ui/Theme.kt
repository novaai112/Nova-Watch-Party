package com.nova.watchparty.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val NovaBase = Color(0xFF14102B)
val NovaInk = Color(0xFF0B0A1E)
val NovaCoral = Color(0xFFFF7A59)
val NovaViolet = Color(0xFF7C5CFF)
val NovaTeal = Color(0xFF2EE6C5)
val NovaGold = Color(0xFFFFC857)
val Glass = Color.White.copy(alpha = 0.10f)
val GlassStrong = Color.White.copy(alpha = 0.18f)
val SubtleText = Color.White.copy(alpha = 0.65f)

val NovaFont = FontFamily.SansSerif // system sans (Roboto on Android); light weights give the airy look

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black, secondary = NovaViolet,
            background = NovaBase, surface = Color(0xFF1C1838), onBackground = Color.White, onSurface = Color.White,
            surfaceVariant = Color(0xFF2A2550), onSurfaceVariant = SubtleText, error = Color(0xFFFF6B6B),
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontFamily = NovaFont, fontWeight = FontWeight.Normal),
            bodyMedium = TextStyle(fontFamily = NovaFont, fontWeight = FontWeight.Normal),
            titleLarge = TextStyle(fontFamily = NovaFont, fontWeight = FontWeight.Light),
        ),
        content = content,
    )
}

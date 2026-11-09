package com.nova.watchparty.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.watchparty.net.Backend
import kotlin.math.cos
import kotlin.math.sin

private data class Blob(val color: Color, val x: Float, val y: Float, val r: Float, val phase: Float)
private val BLOBS = listOf(
    Blob(Color(0xFFFF6B3D), 0.15f, 0.20f, 0.55f, 0f),
    Blob(Color(0xFF7C3AED), 0.85f, 0.15f, 0.60f, 1.7f),
    Blob(Color(0xFF2563EB), 0.20f, 0.75f, 0.65f, 3.1f),
    Blob(Color(0xFFEC4899), 0.80f, 0.60f, 0.50f, 4.4f),
    Blob(Color(0xFFFFB020), 0.55f, 0.95f, 0.45f, 5.6f),
)

/** The soft, slowly moving blurred-colour backdrop used across the app. */
@Composable
fun NovaBackground(modifier: Modifier = Modifier, dim: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    val t by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(40000, easing = LinearEasing)), label = "t"
    )
    Box(modifier.fillMaxSize().background(NovaBase)) {
        Canvas(Modifier.fillMaxSize()) {
            BLOBS.forEach { b ->
                val cx = (b.x + 0.06f * sin(t + b.phase)) * size.width
                val cy = (b.y + 0.05f * cos(t * 0.8f + b.phase)) * size.height
                val rad = b.r * size.maxDimension * 0.7f
                drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = 0.55f), Color.Transparent), Offset(cx, cy), rad), rad, Offset(cx, cy))
            }
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        content()
    }
}

/** Original wordmark: four-point star + "nova". */
@Composable
fun NovaLogo(height: Dp = 32.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(height * 0.9f)) {
            val w = size.width; val c = w / 2
            val p = Path().apply {
                moveTo(c, 0f); cubicTo(c * 1.08f, c * 0.75f, c * 1.25f, c * 0.92f, w, c)
                cubicTo(c * 1.25f, c * 1.08f, c * 1.08f, c * 1.25f, c, w)
                cubicTo(c * 0.92f, c * 1.25f, c * 0.75f, c * 1.08f, 0f, c)
                cubicTo(c * 0.75f, c * 0.92f, c * 0.92f, c * 0.75f, c, 0f); close()
            }
            drawPath(p, Brush.linearGradient(listOf(NovaGold, NovaCoral)))
        }
        Spacer(Modifier.width(height * 0.12f))
        Text("nova", fontSize = (height.value * 0.95f).sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-1).sp)
    }
}

fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp), alpha: Float = 0.10f) =
    this.clip(shape)
        .background(Color.White.copy(alpha = alpha), shape)
        .border(1.dp, Color.White.copy(alpha = (alpha * 1.5f).coerceAtMost(0.35f)), shape)

private val AVATAR_COLORS = listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6))

@Composable
fun Avatar(url: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val shape = CircleShape
    val m = modifier
        .size(size)
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    if (url.isNotBlank()) {
        AsyncImage(Backend.absolute(url), name, m, contentScale = ContentScale.Crop)
    } else {
        val colorIndex = remember(name) { (name.hashCode().and(0x7fffffff)) % AVATAR_COLORS.size }
        Box(m.background(AVATAR_COLORS[colorIndex]), Alignment.Center) {
            Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        }
    }
}

@Composable
fun TopBarBack(onBack: () -> Unit, modifier: Modifier = Modifier, title: @Composable () -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Box(Modifier.weight(1f), Alignment.Center) { title() }
        Row(Modifier.widthIn(min = 48.dp), horizontalArrangement = Arrangement.End, content = trailing)
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Color(0xFF2A2550), Color(0xFF1C1838)))
                else Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.25f)))
            )
            .border(
                1.dp,
                if (enabled) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        Alignment.Center,
    ) { Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp) }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
}

// ---------------------------------------------------------------- Previews

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "NovaBackground")
@Composable
private fun PreviewNovaBackground() {
    NovaTheme {
        NovaBackground {
            NovaLogo(height = 36.dp, modifier = androidx.compose.ui.Modifier.align(Alignment.Center))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "NovaLogo")
@Composable
private fun PreviewNovaLogo() {
    NovaTheme {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.padding(24.dp),
            Alignment.Center,
        ) { NovaLogo(height = 40.dp) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "PillButton")
@Composable
private fun PreviewPillButton() {
    NovaTheme {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            PillButton("JOIN ROOM", onClick = {})
            PillButton("DISABLED", onClick = {}, enabled = false)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "Avatar")
@Composable
private fun PreviewAvatar() {
    NovaTheme {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.padding(16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Avatar("", "Alice", 48.dp)
            Avatar("", "Bob", 48.dp)
            Avatar("", "Zara", 48.dp)
        }
    }
}

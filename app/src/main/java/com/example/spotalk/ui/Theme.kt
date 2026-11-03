package com.example.spotalk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF121212)
    val Elevated = Color(0xFF1F1F1F)
    val Highlight = Color(0xFF2A2A2A)
    val Card = Color(0xFF282828)
    val Green = Color(0xFF1ED760)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF7A7A7A)

    /** צבע "רקע" קבוע לפי שם — במקום צבע שנלקח מעטיפת האלבום */
    private val tints = listOf(
        Color(0xFF4A2D7A), Color(0xFF1E5A6B), Color(0xFF7A2D3A), Color(0xFF2D5A3A),
        Color(0xFF6B4A1E), Color(0xFF2D3A7A), Color(0xFF5A2D6B), Color(0xFF3A5A5A)
    )

    /** צבע אחיד, כשההגדרה "כל העטיפות באותו צבע" פעילה */
    val UniformTint = Color(0xFF3A3A3A)

    /** מתעדכן מההגדרות. מצב של Compose — כל המסכים מתרעננים כשמשנים אותו */
    var uniformTiles by mutableStateOf(false)

    fun tintFor(key: String): Color = when {
        uniformTiles -> UniformTint
        key.isEmpty() -> tints[0]
        else -> tints[Math.floorMod(key.hashCode(), tints.size)]
    }
}

private val SpotifyLikeColors = darkColorScheme(
    primary = Palette.Green,
    onPrimary = Color.Black,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Background,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.TextSecondary,
    secondaryContainer = Palette.Highlight,
    onSecondaryContainer = Palette.TextPrimary
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun TextPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpotifyLikeColors, typography = AppTypography, content = content)
}

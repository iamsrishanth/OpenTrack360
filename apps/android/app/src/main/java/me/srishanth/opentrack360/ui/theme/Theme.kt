package me.srishanth.opentrack360.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OpenTrackColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2EAE2),
    onPrimaryContainer = Color(0xFF103F36),
    secondary = Color(0xFFB66A2D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF45200A),
    tertiary = Color(0xFF5C6380),
    background = Color(0xFFF7F6F1),
    onBackground = Color(0xFF19231F),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF19231F),
    surfaceVariant = Color(0xFFE8E8DF),
    onSurfaceVariant = Color(0xFF4A544F),
    error = Color(0xFFB3261E),
)

@Composable
fun OpenTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenTrackColors,
        content = content,
    )
}

package dev.r0mai.gpsvisualizer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF2C7A4B)
private val GreenLight = Color(0xFF52B788)
private val Amber = Color(0xFFF5A623)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = Amber,
    background = Color(0xFF12161C),
    surface = Color(0xFF1B2028),
)

private val LightColors = lightColorScheme(
    primary = Green,
    secondary = Amber,
)

@Composable
fun GpsVisualizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

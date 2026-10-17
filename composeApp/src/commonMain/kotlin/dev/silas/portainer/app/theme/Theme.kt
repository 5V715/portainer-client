package dev.silas.portainer.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0B6EA8),
    secondary = Color(0xFF4F6B7A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FCBF5),
    secondary = Color(0xFFB4CAD6),
)

@Composable
fun PortainerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}

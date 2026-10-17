package dev.silas.portainer.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Portainer blue is #13BEF9; text/buttons on white need a darker shade (5.6:1), so light uses #0A6E9E. */
val PortainerBlue = Color(0xFF13BEF9)

private val Light = lightColorScheme(
    primary = Color(0xFF0A6E9E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE8F8),
    onPrimaryContainer = Color(0xFF002C42),
    secondary = Color(0xFF4A6272),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E6EE),
    onSecondaryContainer = Color(0xFF0B1E29),
    tertiary = Color(0xFF7A5200),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE4EA),
    onSurfaceVariant = Color(0xFF40484D),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F8),
    surfaceContainer = Color(0xFFEBF0F3),
    surfaceContainerHigh = Color(0xFFE5EAED),
    surfaceContainerHighest = Color(0xFFDFE4E7),
)
private val Dark = darkColorScheme(
    primary = PortainerBlue,
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004B69),
    onPrimaryContainer = Color(0xFFC2E8FF),
    secondary = Color(0xFFB3CAD7),
    onSecondary = Color(0xFF1D333F),
    secondaryContainer = Color(0xFF344A57),
    onSecondaryContainer = Color(0xFFCFE6F3),
    tertiary = Color(0xFFF2BF52),
    onTertiary = Color(0xFF402D00),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF40484D),
    onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF40484D),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C20),
    surfaceContainer = Color(0xFF1B2024),
    surfaceContainerHigh = Color(0xFF262B2E),
    surfaceContainerHighest = Color(0xFF313639),
)

@Composable
fun PortainerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}

package hu.orajegyzet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Visszafogott, modern Material 3 színvilág. A téma a mód szerint vált:
 *  - ONLINE: hűvös, nyugodt zöld-pala (felhő-jelleg)
 *  - OFFLINE: meleg borostyán-tinta (a sárga füzethez illő, „a készüléken" érzet)
 */

private val OnlineScheme = lightColorScheme(
    primary = Color(0xFF2F6B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9E5D6),
    onPrimaryContainer = Color(0xFF09271F),
    secondary = Color(0xFF4A6359),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFE6EEE9),
    onSurfaceVariant = Color(0xFF49524E),
    outline = Color(0xFF7A847F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val OfflineScheme = lightColorScheme(
    primary = Color(0xFF8A6A1B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE3A2),
    onPrimaryContainer = Color(0xFF2A1F00),
    secondary = Color(0xFF6E5D3F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFCF8F0),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF0E6D2),
    onSurfaceVariant = Color(0xFF514633),
    outline = Color(0xFF837660),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun OraJegyzetTheme(online: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (online) OnlineScheme else OfflineScheme,
        typography = Typography(),
        content = content
    )
}

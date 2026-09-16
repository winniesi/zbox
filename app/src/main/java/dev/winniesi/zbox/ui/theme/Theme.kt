package dev.winniesi.zbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EB8FF),
    onPrimary = Color(0xFF003055),
    primaryContainer = Color(0xFF1E3A5C),
    onPrimaryContainer = Color(0xFFCCE4FF),
    secondary = Color(0xFFBFC8DC),
    onSecondary = Color(0xFF293141),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD5),
)

/** 深色为主的管理工具，浅色模式沿用同一深色面板以保持品牌一致。 */
@Composable
fun ZBoxTheme(_darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}

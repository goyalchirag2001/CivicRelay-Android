package org.civicrelay.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val deep = darkColorScheme(
    primary = Color(0xFF6DE2CC), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF19584F), onPrimaryContainer = Color(0xFFC5FFF0),
    secondary = Color(0xFFABC8EA), onSecondary = Color(0xFF102D50),
    background = Color(0xFF0C151C), onBackground = Color(0xFFF0F6F7),
    surface = Color(0xFF15232C), onSurface = Color(0xFFF0F6F7),
    surfaceVariant = Color(0xFF243742), onSurfaceVariant = Color(0xFFBDCDD2),
    error = Color(0xFFFF9B99), errorContainer = Color(0xFF56262B),
    onErrorContainer = Color(0xFFFFD8D8)
)
private val light = lightColorScheme(
    primary = Color(0xFF006B5C), onPrimary = Color.White,
    primaryContainer = Color(0xFFC2F4E5), onPrimaryContainer = Color(0xFF063A31),
    secondary = Color(0xFF2D5984), onSecondary = Color.White,
    background = Color(0xFFF2F7F6), onBackground = Color(0xFF132B32),
    surface = Color.White, onSurface = Color(0xFF152E34),
    surfaceVariant = Color(0xFFE2EEED), onSurfaceVariant = Color(0xFF4B6268),
    error = Color(0xFFB42A34), errorContainer = Color(0xFFFFE1E2),
    onErrorContainer = Color(0xFF7C1521)
)

@Composable fun CivicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) deep else light, content = content)
}

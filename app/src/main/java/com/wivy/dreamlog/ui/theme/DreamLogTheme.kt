package com.wivy.dreamlog.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7C8EB),
    onPrimary = Color(0xFF20304F),
    secondary = Color(0xFFC2C6D6),
    onSecondary = Color(0xFF2C303B),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
)

@Composable
fun DreamLogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FallbackDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

package com.wivy.dreamlog.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DreamLogShapes = Shapes(
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DreamLogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current

    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(context),
        shapes = DreamLogShapes,
        content = content,
    )
}

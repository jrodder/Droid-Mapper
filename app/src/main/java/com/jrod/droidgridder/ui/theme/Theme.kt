package com.jrod.droidgridder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF6EE7B7),
    background = Color(0xFF101418),
    surface = Color(0xFF171C22),
)

@Composable
fun DroidGridderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

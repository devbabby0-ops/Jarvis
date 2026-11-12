package com.jarvis.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JColors {
    val Bg = Color(0xFF02060D)
    val Panel = Color(0xFF0A1628)
    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF2979FF)
    val Amber = Color(0xFFFFB300)
    val Green = Color(0xFF00E676)
    val Dim = Color(0xFF7A8CA5)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JColors.Cyan, background = JColors.Bg, surface = JColors.Panel,
            onSurface = Color.White, onBackground = Color.White, onPrimary = Color.Black
        ),
        content = content
    )
}

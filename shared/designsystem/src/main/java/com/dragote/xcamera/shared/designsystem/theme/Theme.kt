package com.dragote.xcamera.shared.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// xCamera has exactly one visual identity — the dark skeuomorphic camera-body chrome — with no
// light-mode variant, so there's no dark/light branching here. Dynamic color (Material You) is
// deliberately not supported: it would pull the palette from the device wallpaper, actively
// fighting the fixed chrome palette this app is built around.
private val XCameraColorScheme = darkColorScheme(
    primary = AppChrome.Accent,
    onPrimary = Color(0xFF1A1006),
    background = Color(0xFF0D0C0B),
    onBackground = AppChrome.ValueColor,
    surface = Color(0xFF171615),
    onSurface = AppChrome.ValueColor,
    surfaceVariant = Color(0xFF131210),
    onSurfaceVariant = AppChrome.LabelColor,
    secondary = AppChrome.LabelColor,
)

@Composable
fun XCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XCameraColorScheme,
        typography = Typography,
        content = content,
    )
}

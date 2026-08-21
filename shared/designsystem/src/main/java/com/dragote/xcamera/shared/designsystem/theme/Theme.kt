package com.dragote.xcamera.shared.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// XCameraTheme's own MaterialTheme colorScheme wraps exactly one visual identity — [MinimalChrome],
// the flat "paper" line-art chrome shared by every screen (`feature:camera` and `feature:settings`
// alike) — with no dark/light branching here. Dynamic color (Material You) is
// deliberately not supported: it would pull the palette from the device wallpaper, actively fighting
// the fixed chrome palette this app is built around.
//
// Built from [MinimalChrome]'s own `Palette.Normal` tokens rather than a bespoke literal palette, so
// this scheme and every [MinimalChrome] call site's direct token reads (`MinimalChrome.Background`/
// `.Ink`) never drift apart. [MinimalChrome]'s own light/dark-body `Palette` switch (`INVERT CHROME`
// in Settings) is deliberately *not* threaded through here — it's a per-screen Canvas-drawn treatment,
// not a real light/dark variant of the whole app's `MaterialTheme`; screens read `MinimalChrome.Ink`/
// `.Background` directly wherever the inverted palette needs to show through.
//
// Material3's own default `error`/`onError` are intentionally left un-overridden — `error` backs
// `feature:settings`' LUT jiggle-to-delete red tint (a semantic destructive-action color deliberately
// kept out of the chrome identity itself), and both would otherwise get replaced by MinimalChrome's
// flat black/white/tan tokens which have no equivalent destructive hue.
private val XCameraColorScheme = lightColorScheme(
    primary = MinimalChrome.Palette.Normal.ink,
    onPrimary = MinimalChrome.Palette.Normal.background,
    background = MinimalChrome.Palette.Normal.background,
    onBackground = MinimalChrome.Palette.Normal.ink,
    surface = MinimalChrome.Palette.Normal.background,
    onSurface = MinimalChrome.Palette.Normal.ink,
    surfaceVariant = MinimalChrome.Palette.Normal.background,
    onSurfaceVariant = MinimalChrome.Palette.Normal.ink,
    secondary = MinimalChrome.Palette.Normal.ink,
)

@Composable
fun XCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XCameraColorScheme,
        typography = Typography,
        content = content,
    )
}

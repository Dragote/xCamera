package com.dragote.xcamera.feature.camera.ui.component

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-shot thumbnail chip — the app's only gallery entry point, matching the design (there's no
 * separate header button). Shows [photoUri] (decoded to a small [ImageBitmap], falling back to the
 * decorative placeholder while loading or if it's null).
 *
 * Bottom-left is its *physical*, not screen-local, home: the Activity is portrait-locked (see
 * AndroidManifest), so this hops between the four screen corners as
 * [rememberDeviceOrientationQuadrant] changes — via the same [cornerForQuadrant]/`Crossfade` corner-hop
 * `HistogramOverlay` uses — landing on whichever corner is *currently* the physical bottom-left from
 * the user's own point of view, cross-fading between corners rather than sliding across the screen.
 * On top of that, [ViewfinderThumbnailChipContent]'s own glyph additionally counter-rotates in place
 * (smoothly, via [rememberUprightRotationDegrees]) so it stays visually upright too — unlike
 * `HistogramOverlay`'s bars, a square 46dp chip has no footprint-swap concern from rotating in place.
 *
 * [modifier] should size this to the full area the chip is allowed to roam across corners of (e.g.
 * `Modifier.fillMaxSize()` over the whole viewfinder), not to the chip's own small size — see
 * `HistogramOverlay`'s own doc for why.
 */
@Composable
fun ViewfinderThumbnailChip(photoUri: Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val quadrant by rememberDeviceOrientationQuadrant()
    Box(modifier = modifier.padding(CornerInset)) {
        Crossfade(
            targetState = quadrant,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 300, easing = CameraChrome.EaseStandard),
            label = "thumbnailCorner",
        ) { activeQuadrant ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = cornerForQuadrant(Alignment.BottomStart, activeQuadrant),
            ) {
                ViewfinderThumbnailChipContent(photoUri = photoUri, onClick = onClick)
            }
        }
    }
}

private val CornerInset = 12.dp

@Composable
private fun ViewfinderThumbnailChipContent(photoUri: Uri?, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photoUri) {
        thumbnail = photoUri?.let { uri ->
            val targetPx = with(density) { 46.dp.roundToPx() }
            decodeThumbnail(context = context, uri = uri, targetPx = targetPx)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }

    val scale by animateFloatAsState(targetValue = if (pressed) 0.92f else 1f, label = "thumbnailChipScale")
    val rotationDegrees by rememberUprightRotationDegrees()

    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(scale)
            .graphicsLayer { rotationZ = rotationDegrees }
            .clip(RoundedCornerShape(8.dp))
            .background(CameraChrome.KnobGradient)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(2.5.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A34), Color(0xFF16241F)))),
    ) {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Decodes a small, downsampled bitmap for [uri] — [ImageDecoder] (API 28+) lets the sample size be
 * picked from the source's real dimensions before allocating, so a multi-megapixel photo doesn't
 * get fully decoded just to end up a 46dp chip; [MediaStore.Images.Media.getBitmap] is the
 * best-effort fallback down to this module's minSdk 26. Returns null on any failure (revoked URI,
 * deleted file, unsupported format) — this is a decorative thumbnail, not something worth crashing
 * or surfacing an error state over.
 */
private suspend fun decodeThumbnail(context: Context, uri: Uri, targetPx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sampleSize = (maxOf(info.size.width, info.size.height) / targetPx).coerceAtLeast(1)
                    decoder.setTargetSampleSize(sampleSize)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

/**
 * A smoothly-animated rotation (degrees) that counters the phone's physical orientation, bucketed
 * to the same 90°-quadrant thresholds [CameraController][com.dragote.xcamera.feature.camera.data.CameraController]
 * uses for the captured photo's own EXIF rotation — so the thumbnail's "upright" matches whatever
 * orientation a photo taken at that same moment would be saved with. Tracks an *unwrapped* angle
 * (not clamped to 0-359) and always steps by the shortest signed delta between buckets, so e.g.
 * 270°→0° animates as a -90° turn rather than spinning the long way around through 180°.
 */
@Composable
private fun rememberUprightRotationDegrees(): State<Float> {
    val quadrant by rememberDeviceOrientationQuadrant()
    val unwrapped = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(quadrant) {
        val bucket = counterRotationDegrees(quadrant)
        val current = unwrapped.floatValue
        val shortestDelta = ((bucket - current) % 360f + 540f) % 360f - 180f
        unwrapped.floatValue = current + shortestDelta
    }

    return animateFloatAsState(
        targetValue = unwrapped.floatValue,
        animationSpec = tween(durationMillis = 300, easing = CameraChrome.EaseStandard),
        label = "thumbnailUprightRotation",
    )
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ViewfinderThumbnailChipPreview() {
    XCameraTheme {
        ViewfinderThumbnailChip(photoUri = null, onClick = {}, modifier = Modifier.fillMaxSize())
    }
}

package com.dragote.xcamera.feature.camera.ui.component

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.abs

/**
 * Last-shot thumbnail chip in the viewfinder's bottom-left corner — the app's only gallery entry
 * point, matching the design (there's no separate header button). Shows [photoUri] (decoded to a
 * small [ImageBitmap], falling back to the decorative placeholder while loading or if it's null),
 * and counter-rotates against the phone's physical orientation so it stays visually upright even
 * though the Activity itself is portrait-locked — the same trick most camera apps use for a
 * gallery-shortcut icon.
 */
@Composable
fun ViewfinderThumbnailChip(photoUri: Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier
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
 *
 * Quadrant switching has [QUADRANT_HYSTERESIS_DEGREES] of hysteresis: holding the phone right at a
 * boundary (45°, 135°, ...) is exactly where the raw sensor reading is noisiest, so without a
 * sticky bias toward whichever quadrant is already active, tiny jitter there flips the bucket back
 * and forth every callback and the thumbnail visibly bounces.
 */
@Composable
private fun rememberUprightRotationDegrees(): State<Float> {
    val context = LocalContext.current
    val unwrapped = remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        var currentQuadrant = 0f
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentQuadrant = nextQuadrant(currentQuadrant, orientation.toFloat())
                val bucket = (360f - currentQuadrant) % 360f
                val current = unwrapped.floatValue
                val shortestDelta = ((bucket - current) % 360f + 540f) % 360f - 180f
                unwrapped.floatValue = current + shortestDelta
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    return animateFloatAsState(
        targetValue = unwrapped.floatValue,
        animationSpec = tween(durationMillis = 300, easing = CameraChrome.EaseStandard),
        label = "thumbnailUprightRotation",
    )
}

private const val QUADRANT_HYSTERESIS_DEGREES = 15f
private val QuadrantCenters = floatArrayOf(0f, 90f, 180f, 270f)

/** Shortest signed angular distance from [b] to [a], in (-180, 180]. */
private fun angularDistance(a: Float, b: Float): Float {
    val d = (a - b) % 360f
    return when {
        d > 180f -> d - 360f
        d < -180f -> d + 360f
        else -> d
    }
}

/**
 * Picks which of the four 90°-quadrant centers [orientation] belongs to, biasing towards
 * [current] by [QUADRANT_HYSTERESIS_DEGREES] so the result doesn't flip back and forth when
 * [orientation] hovers near a boundary.
 */
private fun nextQuadrant(current: Float, orientation: Float): Float {
    var best = current
    var bestDistance = Float.MAX_VALUE
    for (center in QuadrantCenters) {
        var distance = abs(angularDistance(orientation, center))
        if (center == current) distance -= QUADRANT_HYSTERESIS_DEGREES
        if (distance < bestDistance) {
            bestDistance = distance
            best = center
        }
    }
    return best
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun ViewfinderThumbnailChipPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            ViewfinderThumbnailChip(photoUri = null, onClick = {})
        }
    }
}

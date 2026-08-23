package com.dragote.xcamera.feature.camera.ui.component.indicator

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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.component.overlay.cornerForQuadrant
import com.dragote.xcamera.feature.camera.ui.component.overlay.counterRotationDegrees
import com.dragote.xcamera.feature.camera.ui.component.overlay.rememberDeviceOrientationQuadrant
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-shot thumbnail chip — hops to whichever screen corner [rememberDeviceOrientationQuadrant]
 * reports as current, staying upright regardless of device rotation. Its housing is a solid white
 * frame with a thin black border — reads as one clean shape over arbitrary live scene content,
 * matching [InfoPill]'s own reasoning.
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
                ViewfinderThumbnailChipContent(
                    photoUri = photoUri,
                    onClick = onClick,
                    rotationDegrees = counterRotationDegrees(activeQuadrant),
                )
            }
        }
    }
}

private val CornerInset = 12.dp

@Composable
private fun ViewfinderThumbnailChipContent(photoUri: Uri?, onClick: () -> Unit, rotationDegrees: Float) {
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

    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(scale)
            .graphicsLayer { rotationZ = rotationDegrees }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(CameraChrome.StrokeWidth, CameraChrome.StrokeColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(2.5.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(CameraChrome.Ink),
    ) {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

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

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ViewfinderThumbnailChipPreview() {
    XCameraTheme {
        ViewfinderThumbnailChip(photoUri = null, onClick = {}, modifier = Modifier.fillMaxSize())
    }
}

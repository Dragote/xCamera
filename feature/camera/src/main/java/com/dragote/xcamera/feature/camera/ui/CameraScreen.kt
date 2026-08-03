package com.dragote.xcamera.feature.camera.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragote.xcamera.feature.camera.di.CameraRepositoryEntryPoint
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.formatShutterSpeed
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.feature.camera.presentation.CameraUiState
import com.dragote.xcamera.feature.camera.presentation.CameraViewModel
import com.dragote.xcamera.feature.camera.ui.component.ExposingIndicator
import com.dragote.xcamera.feature.camera.ui.component.ExposureDial
import com.dragote.xcamera.feature.camera.ui.component.FlashLever
import com.dragote.xcamera.feature.camera.ui.component.GridLever
import com.dragote.xcamera.feature.camera.ui.component.IsoDial
import com.dragote.xcamera.feature.camera.ui.component.LensDial
import com.dragote.xcamera.feature.camera.ui.component.ModeLever
import com.dragote.xcamera.feature.camera.ui.component.ShutterButton
import com.dragote.xcamera.feature.camera.ui.component.ShutterSpeedDial
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderGridOverlay
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderThumbnailChip
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.feature.camera.ui.theme.grainTexture
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.designsystem.component.ErrorState
import com.ramcosta.composedestinations.annotation.Destination
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * "Quiet window" `ui/CameraScreen`'s post-mode-switch `LaunchedEffect` waits for — [debounce] resets
 * this timer every time the live auto-ISO/shutter reading actually changes, so pinning only proceeds
 * once 3A has genuinely stopped moving, not just after a fixed delay that a large EV swing could
 * easily still be mid-convergence past.
 */
private const val ManualExposurePinDelayMs = 300L

/** Hard ceiling on the total wait, in case 3A never quite goes fully quiet (e.g. flicker) — pins on
 *  whatever the latest reading is once this elapses rather than stalling the mode switch forever. */
private const val MaxManualPinWaitMs = 1500L

private val requiredPermissions: List<String> = buildList {
    add(Manifest.permission.CAMERA)
    // MediaStore inserts on API 29+ don't need this; only pre-Q devices do.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/**
 * Requested independently of [requiredPermissions] — denying it should only mean no gallery
 * thumbnail preview, not blocking the entire camera screen behind the hard permission gate. Null
 * pre-Q, where the (already hard-gated) WRITE_EXTERNAL_STORAGE covers reads on the legacy storage
 * model too.
 */
private val galleryReadPermission: String? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

@Destination
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> viewModel.onPermissionResult(results.values.all { it }) }

    LaunchedEffect(Unit) {
        val alreadyGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    when (uiState.permissionStatus) {
        CameraPermissionStatus.Granted -> CameraContent(viewModel = viewModel, uiState = uiState)
        CameraPermissionStatus.Denied -> ErrorState(
            message = "Camera permission is required to use xCamera",
            onRetry = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
        )
        CameraPermissionStatus.Unknown -> Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * `bindCamera` is the one camera operation `ui/` calls directly against [CameraRepository] rather
 * than through [CameraViewModel] — see [CameraRepository]'s doc for why. Uses the application
 * `Context` (not [LocalContext] directly) since [EntryPointAccessors.fromApplication] requires it.
 */
@Composable
private fun rememberCameraRepository(): CameraRepository {
    val appContext = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(appContext, CameraRepositoryEntryPoint::class.java).cameraRepository()
    }
}

/**
 * Full-screen skeuomorphic chrome ported from the "Camera App UI v3" design: a graphite body with
 * FLASH/GRID/MODE levers above the viewfinder and a LENS/shutter/exposure deck below it. The raw
 * [TextureView] preview itself is untouched in layout terms, just re-framed, with a real
 * [ViewfinderGridOverlay] drawn on top of it now. The former "ZOOM" dial is gone — zoom doesn't exist
 * as a feature in xCamera. In its place, to the right of [LensDial]/[ShutterButton] (which stay
 * paired together on the left), the deck shows either a single [ExposureDial] (auto mode — real
 * Camera2 AE exposure compensation) or the independent [IsoDial]+[ShutterSpeedDial] pair (manual
 * mode), never both. MODE ([ModeLever]) is now the *only* way to switch between them — tapping it
 * always calls [CameraViewModel.onManualModeToggled] regardless of current state, which flips
 * [CameraUiState.manualModeEnabled] (a no-op if the current lens has no manual ISO/shutter stops to
 * offer). Manual exposure itself still drives through [CameraViewModel.setManualExposure] (Camera2's
 * `CONTROL_AE_MODE_OFF` fixes ISO and shutter speed together, there's no "ISO manual, shutter auto"
 * mode).
 *
 * The thumbnail chip shows [latestGalleryUri] (the actual last photo in the device's gallery,
 * queried once permission allows it — see [galleryReadPermission]) until a fresh capture replaces
 * it with [CameraUiState.lastSavedUri]; either way it's just a fallback chain feeding one URI into
 * [ViewfinderThumbnailChip], which owns the image decoding and orientation-reactive rotation.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun CameraContent(viewModel: CameraViewModel, uiState: CameraUiState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraRepository = rememberCameraRepository()

    // A raw Camera2 preview surface (unlike CameraX's Preview.SurfaceProvider) has to come from a
    // real Android view's SurfaceTexture that ui/ owns and sizes itself — see CameraRepository's own
    // doc for why bindCamera takes a plain Surface now. previewSurface/previewViewSize are only set
    // once the TextureView is actually attached and measured; textureView.surfaceTexture is read
    // fresh (not cached) below since it's still the same object for as long as previewSurface is
    // non-null.
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var previewViewSize by remember { mutableStateOf(IntSize.Zero) }
    val textureView = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    previewSurface = Surface(surfaceTexture)
                    previewViewSize = IntSize(width, height)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    previewViewSize = IntSize(width, height)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    cameraRepository.unbindCamera()
                    previewSurface?.release()
                    previewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
    }
    var gridEnabled by remember { mutableStateOf(false) }
    var latestGalleryUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopOrientationListener()
            // Idempotent if onSurfaceTextureDestroyed already ran (e.g. the view was detached before
            // this composable left composition) — CameraController.unbindCamera is a no-op with
            // nothing bound. Still needed as a backstop for the case where the TextureView isn't torn
            // down as part of this composable leaving composition.
            cameraRepository.unbindCamera()
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            coroutineScope.launch { latestGalleryUri = viewModel.latestGalleryPhotoUri() }
        }
    }

    LaunchedEffect(Unit) {
        val permission = galleryReadPermission
        val alreadyGranted = permission == null ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            latestGalleryUri = viewModel.latestGalleryPhotoUri()
        } else {
            galleryPermissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onLensesLoaded(viewModel.listBackLenses())
    }

    // Called directly against the injected repository, not the ViewModel — bindCamera needs
    // Compose's LifecycleOwner + a raw preview Surface, which CameraViewModel must never import.
    // Re-runs on a lens switch (rebinding against the same Surface, CameraController tears down and
    // reopens the session for the new lens) and whenever the TextureView's SurfaceTexture itself
    // changes (first becomes available, or is resized by a layout pass) since the surface's buffer
    // size/preview transform both depend on the chosen lens's supported preview sizes.
    LaunchedEffect(previewSurface, previewViewSize, uiState.selectedLens) {
        val surface = previewSurface ?: return@LaunchedEffect
        val surfaceTexture = textureView.surfaceTexture ?: return@LaunchedEffect
        val viewWidth = previewViewSize.width
        val viewHeight = previewViewSize.height
        if (viewWidth == 0 || viewHeight == 0) return@LaunchedEffect

        val previewSize = cameraRepository.previewOutputSize(uiState.selectedLens, viewWidth, viewHeight)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        textureView.setTransform(previewFillTransform(viewWidth, viewHeight, previewSize))

        cameraRepository.bindCamera(
            lifecycleOwner = lifecycleOwner,
            surface = surface,
            lens = uiState.selectedLens,
        )
    }

    LaunchedEffect(uiState.flashMode) {
        viewModel.setFlashMode(uiState.flashMode)
    }

    // MANUAL_SENSOR/SENSOR_INFO_SENSITIVITY_RANGE/SENSOR_INFO_EXPOSURE_TIME_RANGE and
    // CONTROL_AE_COMPENSATION_RANGE/CONTROL_AE_COMPENSATION_STEP are all per-physical-lens, not
    // per-device, so both re-query on every lens switch rather than once — see
    // CameraViewModel.manualIsoCapability/aeCompensationCapability.
    LaunchedEffect(uiState.selectedLens) {
        viewModel.onManualIsoCapabilityChanged(viewModel.manualIsoCapability(uiState.selectedLens))
        viewModel.onAeCompensationCapabilityChanged(viewModel.aeCompensationCapability(uiState.selectedLens))
    }

    // Manual mode's *visible* dials appear the instant ModeLever is tapped (manualModeEnabled), but
    // the camera itself doesn't actually lock exposure (CONTROL_AE_MODE_OFF) until 3A has genuinely
    // gone quiet (manualExposurePinned) — a fixed delay isn't enough here: a small EV nudge settles in
    // a couple of frames, but a large compensation swing (say auto→manual right after dragging several
    // stops of EV) can take much longer to actually converge, and a fixed short wait was pinning on a
    // reading 3A hadn't finished moving toward yet, which is exactly what made the switch visibly jump.
    // debounce restarts its quiet-window every time either live reading changes, so this only proceeds
    // once both have genuinely stopped moving; withTimeoutOrNull is a safety net in case 3A never
    // fully quiets down (e.g. flicker), so this can't stall forever. Cancels itself (via LaunchedEffect's
    // key) if manual mode is exited again before it fires.
    LaunchedEffect(uiState.manualModeEnabled) {
        if (uiState.manualModeEnabled) {
            withTimeoutOrNull(MaxManualPinWaitMs) {
                snapshotFlow { uiState.liveAutoIso to uiState.liveAutoExposureTimeNs }
                    .debounce(ManualExposurePinDelayMs)
                    .first()
            }
            viewModel.onManualExposurePinningReady()
        }
    }

    // Prefers the exact raw liveAutoIso/liveAutoExposureTimeNs reading over the rounded-to-nearest-
    // stop selectedIsoIndex/selectedShutterIndex whenever it's available — rounding to the nearest
    // standard-ladder stop is itself a visible brightness step, which is exactly the discontinuity
    // this is trying to avoid on the first pin after a mode switch. Falls back to the ladder value
    // once the user actually drags a dial themselves (see CameraUiState.liveAutoIso's own doc).
    LaunchedEffect(
        uiState.manualExposurePinned,
        uiState.selectedIsoIndex,
        uiState.selectedShutterIndex,
        uiState.isoStops,
        uiState.shutterStops,
        uiState.liveAutoIso,
        uiState.liveAutoExposureTimeNs,
    ) {
        val pinnedIso = (uiState.liveAutoIso ?: uiState.isoStops.getOrNull(uiState.selectedIsoIndex))
            .takeIf { uiState.manualExposurePinned }
        val pinnedShutterNs = (uiState.liveAutoExposureTimeNs ?: uiState.shutterStops.getOrNull(uiState.selectedShutterIndex))
            .takeIf { uiState.manualExposurePinned }
        viewModel.setManualExposure(pinnedIso, pinnedShutterNs)
    }

    // Only meaningful while auto-exposure is active — Camera2 ignores CONTROL_AE_EXPOSURE_COMPENSATION
    // under CONTROL_AE_MODE_OFF (see CameraController.buildPreviewRequest), so this stays harmless to
    // keep pushing during manual mode too rather than needing its own manualModeEnabled guard.
    LaunchedEffect(uiState.selectedAeCompensationIndex, uiState.aeCompensationStops) {
        viewModel.setExposureCompensation(uiState.aeCompensationStops.getOrElse(uiState.selectedAeCompensationIndex) { 0 })
    }

    LaunchedEffect(uiState.captureError) {
        val error = uiState.captureError
        if (error != null) {
            Toast.makeText(context, "Couldn't save photo: $error", Toast.LENGTH_SHORT).show()
        }
    }

    fun capture() {
        coroutineScope.launch {
            viewModel.onCaptureStarted()
            when (val result = viewModel.takePhoto()) {
                is Result.Success -> viewModel.onPhotoSaved(result.data)
                is Result.Error -> viewModel.onCaptureError(result.error.name)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CameraChrome.BodyGradient).grainTexture(alpha = 0.35f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 26.dp, end = 26.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FlashLever(
                        flashOn = uiState.flashMode == FlashMode.ON,
                        onToggle = viewModel::onFlashModeToggled,
                    )
                    GridLever(checked = gridEnabled, onToggle = { gridEnabled = !gridEnabled })
                    ModeLever(
                        manual = uiState.manualModeEnabled,
                        onToggle = viewModel::onManualModeToggled,
                    )
                }
                Seam(modifier = Modifier.padding(top = 16.dp, start = 12.dp, end = 12.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CameraChrome.ViewfinderBezelGradient)
                    .padding(9.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CameraChrome.ViewfinderInsetColor),
                ) {
                    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
                    ViewfinderGridOverlay(visible = gridEnabled, modifier = Modifier.fillMaxSize())
                    ExposingIndicator(
                        visible = uiState.isCapturing,
                        durationLabel = if (uiState.manualModeEnabled) {
                            uiState.shutterStops.getOrNull(uiState.selectedShutterIndex)?.let(::formatShutterSpeed)
                        } else {
                            null
                        },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                    ViewfinderThumbnailChip(
                        photoUri = uiState.lastSavedUri ?: latestGalleryUri,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = uiState.lastSavedUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }
            }

            Seam(modifier = Modifier.padding(top = 16.dp, start = 12.dp, end = 12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CameraChrome.DeckGradient)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LensDial(
                        lenses = uiState.availableLenses,
                        selectedLens = uiState.selectedLens,
                        onLensSelected = viewModel::onLensSelected,
                        modifier = Modifier.weight(1f),
                    )
                    ShutterButton(
                        enabled = !uiState.isCapturing,
                        onCapture = ::capture,
                    )
                    // weight(2f) matches ISO+SHUTTER's combined footprint in manual mode exactly (Lens
                    // stays weight(1f) either way, so its own width never differs between modes) —
                    // ExposureOrManualDials handles the 1.5x-width/centered auto-mode sizing and the
                    // split/merge animation internally, entirely within that one allocated slot.
                    ExposureOrManualDials(
                        uiState = uiState,
                        viewModel = viewModel,
                        modifier = Modifier.weight(2f),
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 130.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                    )
                }
            }
        }
    }
}

/**
 * Auto shows one EXPOSURE dial at 1.5x a single manual dial's width, centered within [modifier]'s
 * footprint; manual shows the ISO/SHUTTER pair side by side with the deck row's own `16.dp` item
 * spacing between them. Just a plain [Crossfade] between the two — no split/merge choreography.
 */
@Composable
private fun ExposureOrManualDials(uiState: CameraUiState, viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    Crossfade(targetState = uiState.manualModeEnabled, modifier = modifier, label = "exposureOrManualDials") { manual ->
        if (manual) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IsoDial(
                    isoStops = uiState.isoStops,
                    selectedIsoIndex = uiState.selectedIsoIndex,
                    onIsoIndexChange = viewModel::onIsoIndexChanged,
                    modifier = Modifier.weight(1f),
                )
                ShutterSpeedDial(
                    shutterStops = uiState.shutterStops,
                    selectedShutterIndex = uiState.selectedShutterIndex,
                    onShutterIndexChange = viewModel::onShutterIndexChanged,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ExposureDial(
                    aeCompensationStops = uiState.aeCompensationStops,
                    aeCompensationStepEv = uiState.aeCompensationStepEv,
                    selectedIndex = uiState.selectedAeCompensationIndex,
                    onIndexChange = viewModel::onAeCompensationIndexChanged,
                    modifier = Modifier.fillMaxWidth(0.75f),
                )
            }
        }
    }
}

private val SeamBrush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.White.copy(alpha = 0.09f)))

@Composable
private fun Seam(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(2.dp).background(SeamBrush))
}

/**
 * Center-crop scale (mirrors the previous `PreviewView`'s `FILL_CENTER` `scaleType`) mapping a raw
 * Camera2 preview buffer onto a [viewWidth]x[viewHeight] [TextureView]. Applied unconditionally
 * rather than gated on a `Display.getRotation()` check the way Google's own Camera2Basic sample's
 * `configureTransform` is: this app's activity is locked to portrait (see `CameraController`'s own
 * `targetRotation` doc), and Compose's `AndroidView(Modifier.fillMaxSize())` forces an exact view
 * size regardless of the `TextureView`'s own measured aspect ratio, so there's no
 * `AutoFitTextureView`-style measure-time aspect-locking to fall back on the way the classic sample
 * has — the crop has to be computed here every time instead. [bufferSize] is width/height-swapped
 * when building the mapping rect since Camera2 expresses it in the sensor's own (landscape, for a
 * portrait-mounted back camera) coordinate convention — see `CameraController.previewOutputSize`'s
 * own doc for the same assumption on the producing side.
 */
private fun previewFillTransform(viewWidth: Int, viewHeight: Int, bufferSize: Size): Matrix {
    val matrix = Matrix()
    if (viewWidth <= 0 || viewHeight <= 0 || bufferSize.width <= 0 || bufferSize.height <= 0) return matrix

    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, bufferSize.height.toFloat(), bufferSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
    val scale = max(viewHeight.toFloat() / bufferSize.width, viewWidth.toFloat() / bufferSize.height)
    matrix.postScale(scale, scale, centerX, centerY)
    return matrix
}
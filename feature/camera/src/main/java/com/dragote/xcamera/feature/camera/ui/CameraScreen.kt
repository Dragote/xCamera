package com.dragote.xcamera.feature.camera.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
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
import com.dragote.xcamera.feature.camera.domain.model.FocusPeakingMask
import com.dragote.xcamera.feature.camera.domain.model.formatShutterSpeed
import com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.feature.camera.presentation.CameraUiState
import com.dragote.xcamera.feature.camera.presentation.CameraViewModel
import com.dragote.xcamera.feature.camera.ui.component.ExposingIndicator
import com.dragote.xcamera.feature.camera.ui.component.ExposureDial
import com.dragote.xcamera.feature.camera.ui.component.FlashLever
import com.dragote.xcamera.feature.camera.ui.component.FocusRing
import com.dragote.xcamera.feature.camera.ui.component.GridLever
import com.dragote.xcamera.feature.camera.ui.component.IsoDial
import com.dragote.xcamera.feature.camera.ui.component.LensDial
import com.dragote.xcamera.feature.camera.ui.component.ModeLever
import com.dragote.xcamera.feature.camera.ui.component.ShutterButton
import com.dragote.xcamera.feature.camera.ui.component.ShutterSpeedDial
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderGridOverlay
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderThumbnailChip
import com.dragote.xcamera.feature.camera.ui.component.ZebraOverlay
import com.dragote.xcamera.feature.camera.ui.gl.CameraPreviewRenderer
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.feature.camera.ui.theme.grainTexture
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.designsystem.component.ErrorState
import com.ramcosta.composedestinations.annotation.Destination
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

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
    val zebraMask by viewModel.zebraMask.collectAsStateWithLifecycle()

    // The live preview no longer goes through a raw Camera2-owned Surface at all — CameraController
    // owns its own preview ImageReader internally (see its own doc for why) and hands each delivered
    // frame to this renderer via CameraRepository.setPreviewFrameListener, which draws it onto
    // textureView's SurfaceTexture with app-owned GLES. Both are remembered once, tied to this
    // composable's lifetime, same as textureView itself always was.
    val renderer = remember { CameraPreviewRenderer() }
    var previewSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var previewViewSize by remember { mutableStateOf(IntSize.Zero) }
    val textureView = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    renderer.start(surfaceTexture)
                    renderer.updateViewMetrics(width, height)
                    previewSurfaceTexture = surfaceTexture
                    previewViewSize = IntSize(width, height)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    renderer.updateViewMetrics(width, height)
                    previewViewSize = IntSize(width, height)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    renderer.stop()
                    cameraRepository.setPreviewFrameListener(null, null)
                    cameraRepository.unbindCamera()
                    previewSurfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
    }
    var gridEnabled by remember { mutableStateOf(false) }
    var latestGalleryUri by remember { mutableStateOf<Uri?>(null) }
    // Top-of-window Y and height (px) of the deck below — DialWheel's mechanical shutters use these
    // to rebuild the deck's own gradient positioned exactly, so a closed dial reads as the deck
    // showing through rather than an opaque patch sitting on top of it.
    var deckTopY by remember { mutableFloatStateOf(0f) }
    var deckHeight by remember { mutableFloatStateOf(0f) }

    // Manual focus ring state (issue #21) — center is fixed at hold-start (see FocusGestureModifier
    // below) and never repositioned by rotation; null means idle (FocusRing plays its own fade-out).
    var focusRingCenter by remember { mutableStateOf<Offset?>(null) }
    var focusRingRotationDegrees by remember { mutableFloatStateOf(0f) }
    var focusLoupeBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var focusPeakingMask by remember { mutableStateOf<FocusPeakingMask?>(null) }
    // Snapshotted once at hold-start (see onHoldStart below), not re-read on every rotation tick —
    // CameraController stops emitting fresh AF-converged readings the moment the first rotation locks
    // a manual distance (see CameraUiState.liveFocusDistanceDiopters's own doc), so this is the one
    // correct "distance a rotation adjusts from" for the gesture's whole duration.
    var focusHoldStartDistance by remember { mutableFloatStateOf(0f) }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopOrientationListener()
            // Idempotent if onSurfaceTextureDestroyed already ran (e.g. the view was detached before
            // this composable left composition) — renderer.stop()/CameraController.unbindCamera are
            // both no-ops with nothing started/bound. Still needed as a backstop for the case where
            // the TextureView isn't torn down as part of this composable leaving composition.
            renderer.stop()
            cameraRepository.setPreviewFrameListener(null, null)
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

    // Called directly against the injected repository, not the ViewModel — bindCamera needs Compose's
    // LifecycleOwner, which CameraViewModel must never import. Re-runs on a lens switch (rebinding for
    // the new lens's own preview size/rotation) and whenever the TextureView's SurfaceTexture itself
    // changes (first becomes available, or is resized by a layout pass). Deliberately *not*
    // re-triggered on lifecycle events (e.g. ON_START after a background/foreground cycle) —
    // CameraController is the sole authority for lifecycle-driven reopen (its own onStateChanged), and
    // previewSurfaceTexture/previewViewSize/selectedLens genuinely don't change across that kind of
    // resume, so there's nothing for this effect to recompute — the renderer re-derives its own crop/
    // rotation transform from each frame's actually-delivered dimensions regardless (see
    // CameraPreviewRenderer's own doc for why that's what actually fixes the aspect-ratio bug a much
    // earlier version of this file tried and failed to work around with a second lifecycle reaction).
    LaunchedEffect(previewSurfaceTexture, previewViewSize, uiState.selectedLens) {
        if (previewSurfaceTexture == null) return@LaunchedEffect
        val viewWidth = previewViewSize.width
        val viewHeight = previewViewSize.height
        if (viewWidth == 0 || viewHeight == 0) return@LaunchedEffect

        cameraRepository.setPreviewFrameListener(renderer.handler, renderer::onPreviewFrame)
        renderer.updateRotation(cameraRepository.previewRotationDegrees(uiState.selectedLens))
        cameraRepository.bindCamera(
            lifecycleOwner = lifecycleOwner,
            previewViewWidth = viewWidth,
            previewViewHeight = viewHeight,
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
        viewModel.onManualFocusCapabilityChanged(viewModel.manualFocusCapability(uiState.selectedLens))
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

    // Periodically refreshes the manual-focus ring's loupe crop + focus-peaking highlight (issue #21)
    // for as long as the hold gesture is active — mirrors the zebra overlay's own "only pay this cost
    // while actually engaged" pattern. TextureView.getBitmap() snapshots exactly what's currently drawn
    // onto its own SurfaceTexture, which CameraPreviewRenderer draws the live preview into via GLES, so
    // this reflects real, current preview content rather than a separate capture path. Runs on the main
    // thread (a View method) but the actual crop + luma/edge-detection work is pushed onto
    // Dispatchers.Default so it doesn't block composition/input while the ring is held.
    LaunchedEffect(focusRingCenter != null) {
        val center = focusRingCenter ?: return@LaunchedEffect
        while (isActive) {
            val fullFrame = runCatching { textureView.getBitmap() }.getOrNull()
            if (fullFrame != null) {
                val crop = withContext(Dispatchers.Default) { cropLoupeBitmap(fullFrame, center) }
                if (crop != null) {
                    focusLoupeBitmap = crop.asImageBitmap()
                    focusPeakingMask = withContext(Dispatchers.Default) { focusPeakingMaskFromBitmap(crop) }
                }
            }
            delay(LoupeRefreshIntervalMs)
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
                val latestUiState by rememberUpdatedState(uiState)
                val viewConfiguration = LocalViewConfiguration.current
                val focusVibrator = rememberFocusVibrator()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CameraChrome.ViewfinderInsetColor)
                        .then(
                            // No gesture detector at all on a lens with no manual-focus capability —
                            // both tap-to-focus and the hold-and-rotate ring are hidden/no-op together
                            // (issue #21's own capability gate). Keyed on manualFocusSupported alone
                            // (not the whole uiState) so an in-progress gesture never gets cancelled
                            // mid-flight by an unrelated state change — see latestUiState above for how
                            // the gesture still reads fresh values without restarting on every change.
                            if (uiState.manualFocusSupported) {
                                Modifier.pointerInput(Unit) {
                                    detectFocusGestures(
                                        viewConfigurationLongPressMs = viewConfiguration.longPressTimeoutMillis,
                                        viewConfigurationTouchSlopPx = viewConfiguration.touchSlop,
                                        onTap = { position ->
                                            if (size.width <= 0 || size.height <= 0) return@detectFocusGestures
                                            viewModel.triggerAutoFocus(
                                                displayXFraction = (position.x / size.width).coerceIn(0f, 1f),
                                                displayYFraction = (position.y / size.height).coerceIn(0f, 1f),
                                            )
                                        },
                                        onHoldStart = { center ->
                                            focusRingCenter = center
                                            focusRingRotationDegrees = 0f
                                            focusLoupeBitmap = null
                                            focusPeakingMask = null
                                            focusHoldStartDistance = latestUiState.liveFocusDistanceDiopters ?: 0f
                                            focusHaptic(focusVibrator)
                                        },
                                        onRotate = { totalRotationRadians ->
                                            focusRingRotationDegrees = Math.toDegrees(totalRotationRadians.toDouble()).toFloat()
                                            val newDistance = manualFocusDistanceForRotation(
                                                startDistanceDiopters = focusHoldStartDistance,
                                                rotationRadians = totalRotationRadians,
                                                maxFocusDistanceDiopters = latestUiState.maxFocusDistanceDiopters,
                                            )
                                            viewModel.setManualFocusDistance(newDistance)
                                        },
                                        onHoldEnd = {
                                            focusRingCenter = null
                                            focusHaptic(focusVibrator)
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
                    ViewfinderGridOverlay(visible = gridEnabled, modifier = Modifier.fillMaxSize())
                    ZebraOverlay(mask = zebraMask, modifier = Modifier.fillMaxSize())
                    FocusRing(
                        center = focusRingCenter,
                        rotationDegrees = focusRingRotationDegrees,
                        loupeImage = focusLoupeBitmap,
                        peakingMask = focusPeakingMask,
                        modifier = Modifier.fillMaxSize(),
                    )
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
                    .onGloballyPositioned {
                        deckTopY = it.positionInRoot().y
                        deckHeight = it.size.height.toFloat()
                    }
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
                        backgroundTopY = deckTopY,
                        backgroundHeight = deckHeight,
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

// Duration of one leg (close or open) of the mechanical dial transition below.
private const val MECHANICAL_DIAL_DURATION_MS = 320

// Beat held fully sealed (shutters closed, indistinguishable from the bare deck — see
// backgroundTopY/backgroundHeight below) between the outgoing dial finishing its close and the
// incoming one starting its open — without this the close and open read as one continuous motion
// instead of two distinct mechanical actions.
private const val MECHANICAL_DIAL_PAUSE_MS = 150L

// How much later the second manual-mode dial (SHUTTER) starts its close/open relative to the first
// (ISO) — small enough to read as "not quite in lockstep" rather than an obvious relay.
private const val MECHANICAL_DIAL_STAGGER_MS = 30L

/**
 * Auto shows one EXPOSURE dial at 1.5x a single manual dial's width, centered within [modifier]'s
 * footprint; manual shows the ISO/SHUTTER pair side by side with the deck row's own `16.dp` item
 * spacing between them.
 *
 * Switching between them plays a mechanical seal/unseal transition (barrel sinks, shutters close —
 * see `DialWheel`'s `closedFraction`) rather than a plain crossfade: [closedFractionPrimary] drives
 * whichever single dial leads (EXPOSURE in auto, ISO in manual); in manual mode, [closedFractionShutter]
 * drives SHUTTER starting [MECHANICAL_DIAL_STAGGER_MS] after it so the two barrels don't move in
 * perfect lockstep. Once both reach `1` (fully sealed — the shutters paint the exact deck gradient at
 * their position, via [backgroundTopY]/[backgroundHeight], so sealed reads as "the deck, uninterrupted"
 * rather than an opaque patch sitting on it — no separate fade-to-invisible needed), which dial set is
 * composed swaps, holds there for [MECHANICAL_DIAL_PAUSE_MS] (so close and open read as two distinct
 * mechanical actions rather than one continuous motion), then both animate back down to `0` to unseal
 * the new set (staggered the same way) — so the outgoing and incoming dials are never on screen, not
 * even sealed, at the same time.
 */
@Composable
private fun ExposureOrManualDials(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    backgroundTopY: Float = 0f,
    backgroundHeight: Float = 0f,
) {
    var displayedManual by remember { mutableStateOf(uiState.manualModeEnabled) }
    val closedFractionPrimary = remember { Animatable(0f) }
    val closedFractionShutter = remember { Animatable(0f) }
    // Which leg closedFraction is currently animating — DialWheel needs this because the sink and
    // shutter bounces are only correct arriving from one particular direction (see `DialWheel`'s
    // `closing` parameter); this is the one place that unambiguously knows it, since it's the one
    // issuing the `animateTo` calls below.
    var closing by remember { mutableStateOf(true) }
    val targetManual by rememberUpdatedState(uiState.manualModeEnabled)

    // Drives CameraController's zebra-stripe analysis stream (see issue #6) — any one of the three
    // exposure dials' own drag gesture (DialWheel.onDragActiveChanged) is enough to turn it on; it's
    // off unless at least one is actively being dragged. Only IsoDial/ShutterSpeedDial are mounted at
    // once in manual mode, and only ExposureDial in auto mode (see the Box below), so at most one of
    // these three is ever really drag-able at a time regardless.
    var isoDragging by remember { mutableStateOf(false) }
    var shutterDragging by remember { mutableStateOf(false) }
    var exposureDragging by remember { mutableStateOf(false) }
    LaunchedEffect(isoDragging, shutterDragging, exposureDragging) {
        viewModel.setZebraAnalysisEnabled(isoDragging || shutterDragging || exposureDragging)
    }
    // Every mode switch unmounts whichever dial set was showing mid-composition (auto's ExposureDial
    // when entering manual, IsoDial/ShutterSpeedDial when leaving it), which cancels that dial's
    // gesture coroutine before it can report onDragActiveChanged(false) if that happened mid-drag
    // (e.g. a lens switch that loses MANUAL_SENSOR capability) — reset all three explicitly on every
    // switch so analysis doesn't stay stuck enabled with no dial left to drive it.
    LaunchedEffect(displayedManual) {
        isoDragging = false
        shutterDragging = false
        exposureDragging = false
    }

    LaunchedEffect(Unit) {
        snapshotFlow { targetManual }.collect { target ->
            if (target != displayedManual) {
                closing = true
                coroutineScope {
                    launch { closedFractionPrimary.animateTo(1f, tween(MECHANICAL_DIAL_DURATION_MS, easing = LinearEasing)) }
                    delay(MECHANICAL_DIAL_STAGGER_MS)
                    closedFractionShutter.animateTo(1f, tween(MECHANICAL_DIAL_DURATION_MS, easing = LinearEasing))
                }
                displayedManual = target
                delay(MECHANICAL_DIAL_PAUSE_MS)
                closing = false
                coroutineScope {
                    launch { closedFractionPrimary.animateTo(0f, tween(MECHANICAL_DIAL_DURATION_MS, easing = LinearEasing)) }
                    delay(MECHANICAL_DIAL_STAGGER_MS)
                    closedFractionShutter.animateTo(0f, tween(MECHANICAL_DIAL_DURATION_MS, easing = LinearEasing))
                }
            }
        }
    }

    Box(modifier = modifier) {
        if (displayedManual) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IsoDial(
                    isoStops = uiState.isoStops,
                    selectedIsoIndex = uiState.selectedIsoIndex,
                    onIsoIndexChange = viewModel::onIsoIndexChanged,
                    modifier = Modifier.weight(1f),
                    onDragActiveChanged = { isoDragging = it },
                    closedFraction = closedFractionPrimary.value,
                    closing = closing,
                    backgroundTopY = backgroundTopY,
                    backgroundHeight = backgroundHeight,
                )
                ShutterSpeedDial(
                    shutterStops = uiState.shutterStops,
                    selectedShutterIndex = uiState.selectedShutterIndex,
                    onShutterIndexChange = viewModel::onShutterIndexChanged,
                    modifier = Modifier.weight(1f),
                    onDragActiveChanged = { shutterDragging = it },
                    closedFraction = closedFractionShutter.value,
                    closing = closing,
                    backgroundTopY = backgroundTopY,
                    backgroundHeight = backgroundHeight,
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
                    onDragActiveChanged = { exposureDragging = it },
                    closedFraction = closedFractionPrimary.value,
                    closing = closing,
                    backgroundTopY = backgroundTopY,
                    backgroundHeight = backgroundHeight,
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

// previewFillTransform (the android.graphics.Matrix-based center-crop applied via
// TextureView.setTransform) was deleted here — the live preview is no longer targeted at a
// SurfaceTexture-backed Surface Camera2 writes into directly, so there's no Matrix for this file to
// compute at all anymore. CameraPreviewRenderer computes the equivalent crop/rotation transform
// itself, as a GL matrix, from each frame's actually-delivered Image dimensions — see its own doc.

/* ── Manual focus (issue #21): tap-to-focus + hold-and-rotate ring gesture ─────────────────────── */

/** Radius (px) a pointer must be from the fixed hold center before its angle starts contributing to
 *  rotation — right at the center, a tiny finger jitter's angle is essentially noise (an infinitesimal
 *  radius amplifies to a huge, meaningless angular swing), so rotation only starts accumulating once
 *  the finger has genuinely moved out into a circular path around the ring's own center. */
private const val MinRotationRadiusPx = 24f

/**
 * Distinguishes a simple tap (short press, negligible movement) from a long-press-and-hold (issue
 * #21's manual focus ring) on the same gesture, mirroring `DialWheel`'s own `awaitEachGesture` +
 * `awaitFirstDown` structure. A drag that moves past [viewConfigurationTouchSlopPx] before the
 * long-press threshold elapses is neither — it's ignored outright (this screen has no pan/swipe
 * gesture over the viewfinder for this to conflict with).
 *
 * While holding, [onRotate] is called on every pointer move with the *total* signed rotation (radians,
 * positive clockwise) accumulated around the fixed hold center since the hold began — computed by
 * unwrapping the raw `atan2` angle across the +-pi wrap boundary on every step, the same "accumulate
 * deltas, not absolute angles" approach true continuous rotation gestures need (an absolute angle
 * alone can't distinguish one full turn from zero turns). [MinRotationRadiusPx] freezes accumulation
 * while the pointer is too close to the center for its angle to be meaningful.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectFocusGestures(
    viewConfigurationLongPressMs: Long,
    viewConfigurationTouchSlopPx: Float,
    onTap: (Offset) -> Unit,
    onHoldStart: (Offset) -> Unit,
    onRotate: (totalRotationRadians: Float) -> Unit,
    onHoldEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTimeMs = System.currentTimeMillis()
        var pointer = down
        var moved = false
        var isLongPress = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
            if (!change.pressed) {
                pointer = change
                break
            }
            if ((change.position - down.position).getDistance() > viewConfigurationTouchSlopPx) moved = true
            if (!moved && System.currentTimeMillis() - downTimeMs >= viewConfigurationLongPressMs) {
                isLongPress = true
                pointer = change
                break
            }
            pointer = change
        }

        when {
            isLongPress -> {
                onHoldStart(down.position)
                var lastAngle = 0f
                var angleInitialized = false
                var totalRotationRadians = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                    if (!change.pressed) break

                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (hypot(dx, dy) >= MinRotationRadiusPx) {
                        val angle = atan2(dy, dx)
                        if (angleInitialized) {
                            var delta = angle - lastAngle
                            if (delta > PI.toFloat()) delta -= (2f * PI.toFloat())
                            if (delta < -PI.toFloat()) delta += (2f * PI.toFloat())
                            totalRotationRadians += delta
                            onRotate(totalRotationRadians)
                        }
                        lastAngle = angle
                        angleInitialized = true
                    }
                    change.consume()
                    pointer = change
                }
                onHoldEnd()
            }
            !moved && pointer.pressed.not() -> onTap(down.position)
            else -> Unit // moved past touch slop without reaching the long-press threshold — ignored.
        }
    }
}

/**
 * Bypasses `View.performHapticFeedback` the same way `DialWheel`'s own tick haptic does (see its own
 * doc for why a plain `vibrator.vibrate(effect)` alone isn't enough on modern Android) — duplicated
 * here rather than shared, per this module's "duplicate a small helper until a second feature needs
 * it, then extract" convention (this is the second occurrence; a future third should factor a shared
 * `shared:common`/`shared:designsystem` haptics helper instead of a third copy). Falls back to a plain
 * `createOneShot` below API 29/33, degrading gracefully rather than assuming iPhone-level tactile
 * fidelity — see this repo's own camera-engineer haptics guidance.
 */
@Composable
private fun rememberFocusVibrator(): Vibrator {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}

private fun focusHaptic(vibrator: Vibrator) {
    if (!vibrator.hasVibrator()) return
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
        VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attributes = android.os.VibrationAttributes.Builder()
            .setUsage(android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()
        vibrator.vibrate(effect, attributes)
    } else {
        vibrator.vibrate(effect)
    }
}

/** How often the loupe crop + focus-peaking mask refresh while the manual focus ring is held — mirrors
 *  the zebra overlay's own ~66ms throttle (see `CameraController.ZebraThrottleMs`'s own doc), slightly
 *  looser since this involves a full `TextureView.getBitmap()` readback, not just an already-in-flight
 *  frame's luma plane. */
private const val LoupeRefreshIntervalMs = 80L

/** Half-width/height (px, in the *displayed* `TextureView`'s own pixel space) of the square crop taken
 *  around the hold center for the loupe — deliberately a *view-pixel*-sized crop, not pre-downscaled,
 *  since [FocusRing] draws it magnified via `Canvas.drawImage`'s own dst-size scaling: cropping small
 *  and then scaling up (rather than cropping already-downscaled content) is what actually shows finer
 *  detail than the naked eye sees in the un-magnified viewfinder. */
private const val LoupeCropRadiusPx = 70

/** [FocusPeakingMask] grid resolution for the loupe crop — coarse enough to read as a handful of
 *  distinct highlighted regions rather than visual noise, matching [FocusRing]'s own drawn cell size. */
private const val FocusPeakingGridColumns = 10
private const val FocusPeakingGridRows = 10

/**
 * Crops a [LoupeCropRadiusPx]-radius square out of [source] (a full [TextureView.getBitmap] snapshot)
 * centered on [center], clamped so the crop never runs off the source bitmap's own edges (which would
 * otherwise throw). Returns `null` if [source] is too small to crop from at all.
 */
private fun cropLoupeBitmap(source: Bitmap, center: Offset): Bitmap? {
    val diameter = LoupeCropRadiusPx * 2
    if (source.width < 1 || source.height < 1) return null
    val maxLeft = (source.width - diameter).coerceAtLeast(0)
    val maxTop = (source.height - diameter).coerceAtLeast(0)
    val left = (center.x - LoupeCropRadiusPx).toInt().coerceIn(0, maxLeft)
    val top = (center.y - LoupeCropRadiusPx).toInt().coerceIn(0, maxTop)
    val width = diameter.coerceAtMost(source.width - left)
    val height = diameter.coerceAtMost(source.height - top)
    if (width <= 0 || height <= 0) return null
    return try {
        Bitmap.createBitmap(source, left, top, width, height)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Converts [crop]'s own ARGB pixels to a plain 0..255 luma [IntArray] (standard Rec. 601 luma weights)
 * and feeds it through the pure, unit-tested [FocusPeakingMask.fromLuma] — this glue function itself
 * is Android-`Bitmap`-typed and so isn't independently unit-tested (per this module's hardware/Android-
 * type-boundary testing convention), but the actual edge-classification math it delegates to is.
 */
private fun focusPeakingMaskFromBitmap(crop: Bitmap): FocusPeakingMask {
    val width = crop.width
    val height = crop.height
    val pixels = IntArray(width * height)
    crop.getPixels(pixels, 0, width, 0, 0, width, height)
    val luma = IntArray(width * height) { i ->
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }
    return FocusPeakingMask.fromLuma(luma, width, height, columns = FocusPeakingGridColumns, rows = FocusPeakingGridRows)
}
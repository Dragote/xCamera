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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragote.xcamera.feature.camera.di.CameraRepositoryEntryPoint
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
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
import com.dragote.xcamera.feature.camera.ui.component.FocusDial
import com.dragote.xcamera.feature.camera.ui.component.FocusRing
import com.dragote.xcamera.feature.camera.ui.component.FocusTapIndicator
import com.dragote.xcamera.feature.camera.ui.component.GridLever
import com.dragote.xcamera.feature.camera.ui.component.HistogramOverlay
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

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

/**
 * Grace window (from the moment a tap fires) the AF-indicator lifecycle waits for
 * [AfConvergenceState.SCANNING] to actually show up before giving up on seeing it at all — a trigger
 * is asynchronous, so `CONTROL_AF_STATE` doesn't necessarily flip to `SCANNING` on the very next
 * capture result; a device that converges faster than a frame (or one whose driver doesn't ever
 * report a scan for a fast tap) just falls through this quickly rather than the indicator hanging
 * around waiting for a scan state that isn't coming.
 */
private const val AfScanStartGraceMs = 250L

/** How long the tap indicator stays visible once AF has genuinely left [AfConvergenceState.SCANNING]
 *  (converged or given up) — a deliberate held beat so a fast convergence still reads as a real
 *  "locked" moment instead of a flicker, per issue #21's own follow-up requirement. */
private const val FocusIndicatorHoldAfterLockMs = 500L

/** Safety net for the whole tap-indicator lifecycle, in case AF state never reports anything useful
 *  at all (e.g. hardware that doesn't populate `CONTROL_AF_STATE`) — the indicator still fades out
 *  eventually rather than lingering forever. */
private const val MaxAfIndicatorWaitMs = 2500L

/** Fraction of the viewfinder's own shorter dimension the manual focus ring's diameter occupies —
 *  see `focusRingDiameter`'s own doc in `CameraContent`. */
private const val FocusRingSizeFraction = 0.92f

/** Fallback ring diameter for the brief window before `previewViewSize` is known — matches
 *  `FocusRing`'s own default parameter value. */
private val FocusRingFallbackDiameter = 156.dp

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
 * [FocusDial] (issue #21 UX rework) joins the deck row too, but only on a lens that actually reports
 * manual-focus capability (`CameraUiState.manualFocusSupported`) — unlike the exposure dials it's not
 * a mode-gated swap, it's simply present or absent. It's the *only* control that actually changes the
 * manual focus distance; the viewfinder's own long-press only ever repositions the focus ring/loupe —
 * see the focus-ring state block and `detectFocusGestures` further down for the full split.
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
    val histogramData by viewModel.histogramData.collectAsStateWithLifecycle()

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

    // Manual focus ring state (issue #21, reworked per the FocusDial UX split — see FocusDial's own
    // doc and the deck row below) — two independent hold signals now feed one derived *loupe source*
    // center: screenHoldPosition (a long-press directly on the viewfinder, see detectFocusGestures) is
    // the *actual* touch point and wins whenever it's held (the more specific signal); dialHeld
    // (FocusDial, held with no screen hold) falls back to the viewfinder's own center. The ring stays
    // up as long as *either* is held — focusLoupeSourceCenter is only null once both are released.
    // Rotation itself (and therefore the real manual focus distance) is now driven *exclusively* by
    // FocusDial — the viewfinder's own long-press only ever repositions where the loupe samples from,
    // it no longer adjusts focus at all (see the viewfinder's own onHoldStart/onHoldEnd below).
    var screenHoldPosition by remember { mutableStateOf<Offset?>(null) }
    var dialHeld by remember { mutableStateOf(false) }
    val focusLoupeSourceCenter = screenHoldPosition
        ?: Offset(previewViewSize.width / 2f, previewViewSize.height / 2f).takeIf { dialHeld }
    // The ring itself, however, always *renders* dead-center on the viewfinder regardless of where the
    // loupe is actually sampling from (on-device-QA follow-up: a ring that visually jumps to the touch
    // point read as broken/inconsistent) — only its content (focusLoupeBitmap/focusPeakingMask, cropped
    // around focusLoupeSourceCenter below) reflects the touch point; the ring's own position never does.
    val focusRingDisplayCenter =
        Offset(previewViewSize.width / 2f, previewViewSize.height / 2f).takeIf { focusLoupeSourceCenter != null }
    var focusRingRotationDegrees by remember { mutableFloatStateOf(0f) }
    var focusLoupeBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var focusPeakingMask by remember { mutableStateOf<FocusPeakingMask?>(null) }
    // Snapshotted once at FocusDial's own hold-start (see its onHoldStart below), not re-read on every
    // rotation tick — CameraController stops emitting fresh AF-converged readings the moment the first
    // rotation locks a manual distance (see CameraUiState.liveFocusDistanceDiopters's own doc), so this
    // is the one correct "distance a rotation adjusts from" for the gesture's whole duration.
    var focusHoldStartDistance by remember { mutableFloatStateOf(0f) }

    // Function-scoped (not nested inside the viewfinder's own Box like before) since both the
    // viewfinder's long-press gesture *and* FocusDial (in the deck row further down) now read/trigger
    // these — rememberUpdatedState is what lets a pointerInput closure that never restarts across
    // recompositions (both gestures use a fixed Unit key, see detectFocusGestures/FocusDial's own
    // pointerInput) still read the *current* uiState instead of whatever uiState happened to be in
    // scope the one time that closure was originally created.
    val latestUiState by rememberUpdatedState(uiState)
    val focusVibrator = rememberFocusVibrator()

    // Tap-to-focus's own visual feedback (on-device-QA follow-up to issue #21) — independent lifecycle
    // from the hold-and-rotate ring above: appears at the tap point, stays up while
    // CameraViewModel.afConvergenceState reports SCANNING, then fades a beat after it settles — see
    // the LaunchedEffect(focusTapToken) below.
    var focusTapPosition by remember { mutableStateOf<Offset?>(null) }
    var focusTapVisible by remember { mutableStateOf(false) }
    // A structural-equality Offset alone can't be relied on to restart a LaunchedEffect on a repeat
    // tap at the exact same pixel (Compose skips the restart if the new key `equals()` the old one) —
    // this increments on every real tap regardless of position, so it's always a genuinely new key.
    var focusTapToken by remember { mutableStateOf(0) }

    // Ring diameter occupies ~92% of the viewfinder's own shorter dimension (on-device-QA follow-up
    // to issue #21 — the ring used to be a small, fixed 156dp regardless of viewfinder size, which
    // read as too small once it was also recentered to the viewfinder's own middle, see
    // focusRingDisplayCenter's own doc above). Falls back to FocusRing's own default before the TextureView
    // has been laid out at least once (previewViewSize still zero) — never actually visible that
    // early, since there's nothing to long-press yet, just keeps this expression total.
    val density = LocalDensity.current
    val minViewfinderDimensionPx = minOf(previewViewSize.width, previewViewSize.height)
    val focusRingDiameter = if (minViewfinderDimensionPx > 0) {
        with(density) { (minViewfinderDimensionPx * FocusRingSizeFraction).toDp() }
    } else {
        FocusRingFallbackDiameter
    }

    // Scales the loupe crop radius by the same ratio FocusRing itself scales its ring band/teeth by
    // (relative to FocusRingReferenceDiameter, the size LoupeCropRadiusPx was originally tuned
    // against) — otherwise the same small crop gets stretched over a much bigger loupe circle and the
    // magnified image turns to mush. Dp/Dp division yields a plain Float scale factor.
    val focusRingScale = focusRingDiameter / FocusRingReferenceDiameter
    val loupeCropRadiusPx = (LoupeCropRadiusPx * focusRingScale).roundToInt().coerceAtLeast(LoupeCropRadiusPx)

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
    LaunchedEffect(focusLoupeSourceCenter != null) {
        val center = focusLoupeSourceCenter ?: return@LaunchedEffect
        while (isActive) {
            val fullFrame = runCatching { textureView.getBitmap() }.getOrNull()
            if (fullFrame != null) {
                val crop = withContext(Dispatchers.Default) { cropLoupeBitmap(fullFrame, center, loupeCropRadiusPx) }
                if (crop != null) {
                    focusLoupeBitmap = crop.asImageBitmap()
                    focusPeakingMask = withContext(Dispatchers.Default) { focusPeakingMaskFromBitmap(crop) }
                }
            }
            delay(LoupeRefreshIntervalMs)
        }
    }

    // Tap-to-focus indicator lifecycle (on-device-QA follow-up to issue #21) — restarts fresh on every
    // real tap via focusTapToken (see its own doc for why a plain Offset key isn't enough). Bounded
    // end-to-end by MaxAfIndicatorWaitMs so hardware that never reports CONTROL_AF_STATE meaningfully
    // still fades the indicator out eventually rather than leaving it stuck on screen.
    LaunchedEffect(focusTapToken) {
        if (focusTapToken == 0) return@LaunchedEffect
        focusTapVisible = true
        withTimeoutOrNull(MaxAfIndicatorWaitMs) {
            // First wait (briefly) for AF to actually start scanning in response to the trigger — the
            // trigger is async, so the very next capture result can still reflect a stale pre-tap
            // state; a device that converges faster than a frame (or never reports a scan at all)
            // just falls through this after a short grace window instead of hanging on it.
            withTimeoutOrNull(AfScanStartGraceMs) {
                viewModel.afConvergenceState.filter { it == AfConvergenceState.SCANNING }.first()
            }
            // Then wait for scanning to actually finish (converged or given up).
            viewModel.afConvergenceState.filter { it != AfConvergenceState.SCANNING }.first()
        }
        delay(FocusIndicatorHoldAfterLockMs)
        focusTapVisible = false
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
                val viewConfiguration = LocalViewConfiguration.current

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CameraChrome.ViewfinderInsetColor)
                        .then(
                            // No gesture detector at all on a lens with no manual-focus capability —
                            // both tap-to-focus and the hold-to-position ring are hidden/no-op together
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
                                            // A fresh Offset value even for a repeat tap at the exact
                                            // same pixel isn't enough on its own to restart a
                                            // LaunchedEffect keyed on structural equality — see
                                            // focusTapToken's own doc for why that's tracked
                                            // separately.
                                            focusTapPosition = position
                                            focusTapToken++
                                        },
                                        onHoldStart = { position ->
                                            // The viewfinder's own long-press now only repositions the
                                            // ring/loupe — it no longer adjusts focus distance at all
                                            // (that moved to FocusDial, in the deck row below, per the
                                            // #21 UX rework). Uses the real touch point directly —
                                            // reversed from the prior "always centered" behavior, which
                                            // is now only what happens when the *dial* alone is held
                                            // (see screenHoldPosition/dialHeld's own doc above).
                                            screenHoldPosition = position
                                            focusLoupeBitmap = null
                                            focusPeakingMask = null
                                            // A hold gesture takes over from any still-visible tap
                                            // indicator so the two focus affordances never overlap.
                                            focusTapPosition = null
                                            focusTapVisible = false
                                            focusHaptic(focusVibrator)
                                        },
                                        onHoldEnd = {
                                            screenHoldPosition = null
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
                    FocusTapIndicator(
                        position = focusTapPosition,
                        visible = focusTapVisible,
                        modifier = Modifier.fillMaxSize(),
                    )
                    FocusRing(
                        center = focusRingDisplayCenter,
                        rotationDegrees = focusRingRotationDegrees,
                        loupeImage = focusLoupeBitmap,
                        peakingMask = focusPeakingMask,
                        diameter = focusRingDiameter,
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
                    HistogramOverlay(
                        data = histogramData,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
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
                    // Only occupies a slot when the current lens actually supports manual focus (same
                    // capability gate the rest of #21 already uses) — mirrors ExposureOrManualDials'
                    // own conditional-content pattern below, just at the whole-dial level rather than
                    // swapping between two dial sets. Hold-without-touching-the-screen centers the ring
                    // on the viewfinder (dialHeld, see its own doc above); rotating/dragging here is the
                    // *only* thing that actually changes the focus value now — the viewfinder's own
                    // long-press only repositions the ring.
                    if (uiState.manualFocusSupported) {
                        FocusDial(
                            focusDistanceDiopters = uiState.liveFocusDistanceDiopters ?: 0f,
                            onHoldStart = {
                                dialHeld = true
                                focusHoldStartDistance = latestUiState.liveFocusDistanceDiopters ?: 0f
                                focusRingRotationDegrees = 0f
                                // Only clear the loupe if the screen isn't already driving one of its
                                // own — a dial-only hold starting fresh gets a clean loupe, but one
                                // starting alongside an already-held screen shouldn't visibly blank out
                                // the loupe the screen hold already has going.
                                if (screenHoldPosition == null) {
                                    focusLoupeBitmap = null
                                    focusPeakingMask = null
                                }
                                focusTapPosition = null
                                focusTapVisible = false
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
                                dialHeld = false
                                focusHaptic(focusVibrator)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
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

/* ── Manual focus (issue #21): tap-to-focus + hold-to-position ring gesture ───────────────────── */

/**
 * Distinguishes a simple tap (short press, negligible movement) from a long-press-and-hold (issue
 * #21's manual focus ring) on the same gesture, mirroring `DialWheel`'s own `awaitEachGesture` +
 * `awaitFirstDown` structure. A drag that moves past [viewConfigurationTouchSlopPx] before the
 * long-press threshold elapses is neither — it's ignored outright (this screen has no pan/swipe
 * gesture over the viewfinder for this to conflict with).
 *
 * While holding, this no longer tracks rotation at all (that moved to `FocusDial`, per the #21 UX
 * rework) — [onHoldStart] fires once with the touch point, then this just waits for release
 * ([onHoldEnd]), consuming pointer events for the hold's whole duration so nothing else on the
 * viewfinder can interpret them meanwhile.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectFocusGestures(
    viewConfigurationLongPressMs: Long,
    viewConfigurationTouchSlopPx: Float,
    onTap: (Offset) -> Unit,
    onHoldStart: (Offset) -> Unit,
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
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                    if (!change.pressed) break
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
 * doc for why a plain `vibrator.vibrate(effect)` alone isn't enough on modern Android) — still a
 * duplicate of that logic, not shared. This project's duplication convention (see root `CLAUDE.md`) was
 * tightened to "abstract at the *second* occurrence" after `ui/component/DialText.kt` got extracted from
 * exactly this kind of copy (issue #25) — this pair (`DialWheel`'s `rememberDialVibrator`/`tickHaptic`
 * and this `rememberFocusVibrator`/`focusHaptic`) is already at that second occurrence and is due for the
 * same treatment (a `shared:designsystem` haptics helper), just not yet done — flagging here rather than
 * silently leaving it as if the old "wait for a third copy" reasoning still applied. Falls back to a
 * plain `createOneShot` below API 29/33, degrading gracefully rather than assuming iPhone-level tactile
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

/**
 * Base half-width/height (px, in the *displayed* `TextureView`'s own pixel space) of the square crop
 * taken around the hold center for the loupe, at [FocusRingReferenceDiameter] — deliberately a
 * *view-pixel*-sized crop, not pre-downscaled, since [FocusRing] draws it magnified via
 * `Canvas.drawImage`'s own dst-size scaling: cropping small and then scaling up (rather than cropping
 * already-downscaled content) is what actually shows finer detail than the naked eye sees in the
 * un-magnified viewfinder. Scaled up alongside the ring's own much-larger-than-default diameter (see
 * `focusRingDiameter`'s own doc in `CameraContent`) so the loupe's magnification factor — not just its
 * on-screen size — stays roughly constant instead of the same small crop getting stretched over a much
 * bigger circle and turning to mush.
 */
private const val LoupeCropRadiusPx = 120

/** Must track [FocusRing]'s own (private) `RingDiameter` reference — the value [LoupeCropRadiusPx] was
 *  tuned against — so `focusRingScale` in `CameraContent` scales the loupe crop by the same ratio
 *  [FocusRing] itself scales its ring band/teeth by. */
private val FocusRingReferenceDiameter = 156.dp


/**
 * Crops a [radiusPx]-radius square out of [source] (a full [TextureView.getBitmap] snapshot) centered
 * on [center], clamped so the crop never runs off the source bitmap's own edges (which would otherwise
 * throw). Returns `null` if [source] is too small to crop from at all.
 */
private fun cropLoupeBitmap(source: Bitmap, center: Offset, radiusPx: Int): Bitmap? {
    val diameter = radiusPx * 2
    if (source.width < 1 || source.height < 1) return null
    val maxLeft = (source.width - diameter).coerceAtLeast(0)
    val maxTop = (source.height - diameter).coerceAtLeast(0)
    val left = (center.x - radiusPx).toInt().coerceIn(0, maxLeft)
    val top = (center.y - radiusPx).toInt().coerceIn(0, maxTop)
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
 *
 * One mask cell per source pixel (`columns = width`, `rows = height`) rather than a coarse downsampled
 * grid — [FocusRing] renders the mask as a scaled-up bitmap overlay (not per-cell rectangles), so
 * pixel-resolution classification is what makes the highlight trace the actual sharp-object silhouette
 * as a thin contour instead of boxing whole regions.
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
    return FocusPeakingMask.fromLuma(luma, width, height, columns = width, rows = height)
}
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
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.dragote.xcamera.feature.camera.ui.component.FlashToggle
import com.dragote.xcamera.feature.camera.ui.component.FocusDial
import com.dragote.xcamera.feature.camera.ui.component.FocusRing
import com.dragote.xcamera.feature.camera.ui.component.FocusTapIndicator
import com.dragote.xcamera.feature.camera.ui.component.HistogramOverlay
import com.dragote.xcamera.feature.camera.ui.component.HorizonLineOverlay
import com.dragote.xcamera.feature.camera.ui.component.IsoDial
import com.dragote.xcamera.feature.camera.ui.component.LensDial
import com.dragote.xcamera.feature.camera.ui.component.LutDial
import com.dragote.xcamera.feature.camera.ui.component.LutResolvingIndicator
import com.dragote.xcamera.feature.camera.ui.component.ModeToggle
import com.dragote.xcamera.feature.camera.ui.component.SettingsButton
import com.dragote.xcamera.feature.camera.ui.component.ShutterButton
import com.dragote.xcamera.feature.camera.ui.component.ShutterSpeedDial
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderGridOverlay
import com.dragote.xcamera.feature.camera.ui.component.ViewfinderThumbnailChip
import com.dragote.xcamera.feature.camera.ui.component.ZebraOverlay
import com.dragote.xcamera.feature.camera.ui.gl.CameraPreviewRenderer
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.designsystem.component.ErrorState
import com.dragote.xcamera.shared.designsystem.haptics.hapticTick
import com.dragote.xcamera.shared.designsystem.haptics.rememberHapticTickVibrator
import com.dragote.xcamera.shared.navigation.SettingsRoutes
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.Direction
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
 *  "locked" moment instead of a flicker. */
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
    navigator: DestinationsNavigator,
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
        CameraPermissionStatus.Granted -> CameraContent(navigator = navigator, viewModel = viewModel, uiState = uiState)
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
 * Full-screen skeuomorphic chrome: a graphite body with FLASH/GRID/MODE levers above the viewfinder
 * and a LENS/shutter/exposure deck below it. The raw [TextureView] preview itself is untouched in
 * layout terms, just re-framed, with a real [ViewfinderGridOverlay] drawn on top of it. Zoom doesn't
 * exist as a feature in xCamera. To the right of [LensDial]/[ShutterButton] (which stay paired
 * together on the left), the deck shows either a single [ExposureDial] (auto mode — real Camera2 AE
 * exposure compensation) or the independent [IsoDial]+[ShutterSpeedDial] pair (manual mode), never
 * both. MODE ([ModeToggle]) is the *only* way to switch between them — tapping it always calls
 * [CameraViewModel.onManualModeToggled] regardless of current state, which flips
 * [CameraUiState.manualModeEnabled] (a no-op if the current lens has no manual ISO/shutter stops to
 * offer). Manual exposure itself still drives through [CameraViewModel.setManualExposure] (Camera2's
 * `CONTROL_AE_MODE_OFF` fixes ISO and shutter speed together, there's no "ISO manual, shutter auto"
 * mode).
 *
 * [FocusDial] joins the deck row too, but only on a lens that actually reports
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
private fun CameraContent(navigator: DestinationsNavigator, viewModel: CameraViewModel, uiState: CameraUiState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraRepository = rememberCameraRepository()
    val zebraMask by viewModel.zebraMask.collectAsStateWithLifecycle()
    val histogramData by viewModel.histogramData.collectAsStateWithLifecycle()
    val cameraSettings by viewModel.cameraSettings.collectAsStateWithLifecycle()
    val isLutResolving by viewModel.isLutResolving.collectAsStateWithLifecycle()
    val luts by viewModel.luts.collectAsStateWithLifecycle()

    // Flips the whole chrome identity's body/ink pairing (Settings' "INVERT CHROME" lever).
    // MinimalChrome.current is a plain object-level mutableStateOf, not a CompositionLocal, because
    // nearly every camera-chrome component reads CameraChrome.Background/.Ink from inside a Canvas draw
    // lambda (not a @Composable context) — see MinimalChrome.kt's own doc for why. Setting it here, once
    // per recomposition, is enough: mutableStateOf's setter already no-ops on an unchanged value, and
    // Canvas's draw lambda still observes snapshot-state reads for redraw invalidation even though the
    // lambda itself isn't @Composable.
    MinimalChrome.current = if (cameraSettings.minimalChromeInverted) {
        MinimalChrome.Palette.Inverted
    } else {
        MinimalChrome.Palette.Normal
    }

    // The live preview never goes through a raw Camera2-owned Surface — CameraController owns its
    // own preview ImageReader internally (see its own doc for why) and hands each delivered frame to
    // this renderer via CameraRepository.setPreviewFrameListener, which draws it onto
    // textureView's SurfaceTexture with app-owned GLES. Both are remembered once, tied to this
    // composable's lifetime.
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
    var latestGalleryUri by remember { mutableStateOf<Uri?>(null) }
    // Top-of-window Y and height (px) of the deck below — DialWheel's mechanical shutters use these
    // to rebuild the deck's own gradient positioned exactly, so a closed dial reads as the deck
    // showing through rather than an opaque patch sitting on top of it.
    var deckTopY by remember { mutableFloatStateOf(0f) }
    var deckHeight by remember { mutableFloatStateOf(0f) }

    // Manual focus ring state — see FocusDial's own doc and the deck row below — two independent hold
    // signals feed one derived *loupe source*
    // center: screenHoldPosition (a long-press directly on the viewfinder, see detectFocusGestures) is
    // the *actual* touch point and wins whenever it's held (the more specific signal); dialHeld
    // (FocusDial, held with no screen hold) falls back to the viewfinder's own center. The ring stays
    // up as long as *either* is held — focusLoupeSourceCenter is only null once both are released.
    // Rotation itself (and therefore the real manual focus distance) is driven *exclusively* by
    // FocusDial — the viewfinder's own long-press only ever repositions where the loupe samples from,
    // it never adjusts focus itself (see the viewfinder's own onHoldStart/onHoldEnd below).
    var screenHoldPosition by remember { mutableStateOf<Offset?>(null) }
    var dialHeld by remember { mutableStateOf(false) }
    val focusLoupeSourceCenter = screenHoldPosition
        ?: Offset(previewViewSize.width / 2f, previewViewSize.height / 2f).takeIf { dialHeld }
    // The ring itself, however, always *renders* dead-center on the viewfinder regardless of where the
    // loupe is actually sampling from — a ring that visually jumps to the touch point reads as
    // broken/inconsistent — only its content (focusLoupeBitmap/focusPeakingMask, cropped around
    // focusLoupeSourceCenter below) reflects the touch point; the ring's own position never does.
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

    // Function-scoped (not nested inside the viewfinder's own Box) since both the
    // viewfinder's long-press gesture *and* FocusDial (in the deck row further down) read/trigger
    // these — rememberUpdatedState is what lets a pointerInput closure that never restarts across
    // recompositions (both gestures use a fixed Unit key, see detectFocusGestures/FocusDial's own
    // pointerInput) still read the *current* uiState instead of whatever uiState happened to be in
    // scope the one time that closure was originally created.
    val latestUiState by rememberUpdatedState(uiState)
    val focusVibrator = rememberHapticTickVibrator()

    // Tap-to-focus's own visual feedback — independent lifecycle
    // from the hold-and-rotate ring above: appears at the tap point, stays up while
    // CameraViewModel.afConvergenceState reports SCANNING, then fades a beat after it settles — see
    // the LaunchedEffect(focusTapToken) below.
    var focusTapPosition by remember { mutableStateOf<Offset?>(null) }
    var focusTapVisible by remember { mutableStateOf(false) }
    // A structural-equality Offset alone can't be relied on to restart a LaunchedEffect on a repeat
    // tap at the exact same pixel (Compose skips the restart if the new key `equals()` the old one) —
    // this increments on every real tap regardless of position, so it's always a genuinely new key.
    var focusTapToken by remember { mutableStateOf(0) }

    // Ring diameter occupies ~92% of the viewfinder's own shorter dimension — see
    // docs/features/camera-capture.md for the sizing rationale. Falls back to FocusRing's own default
    // before the TextureView has been laid out at least once (previewViewSize still zero) — never
    // actually visible that early, since there's nothing to long-press yet, just keeps this expression
    // total.
    val density = LocalDensity.current
    val minViewfinderDimensionPx = minOf(previewViewSize.width, previewViewSize.height)
    val focusRingDiameter = if (minViewfinderDimensionPx > 0) {
        with(density) { (minViewfinderDimensionPx * FocusRingSizeFraction).toDp() }
    } else {
        FocusRingFallbackDiameter
    }

    // Scales the loupe crop radius by the same ratio FocusRing itself scales its ring band/teeth by
    // (relative to FocusRingReferenceDiameter, the reference size LoupeCropRadiusPx is tuned against)
    // — otherwise the same small crop gets stretched over a much bigger loupe circle and the
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
    // rotation transform from each frame's actually-delivered dimensions regardless. See
    // CameraController's own doc and docs/features/camera-capture.md for why a second,
    // screen-owned lifecycle reaction here is deliberately avoided.
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

    // Resolving CameraSettings.selectedLutId/lutIntensityPercent into an actual parsed LUT
    // (CameraRepository.setLut) is driven by CameraViewModel's own init block, not from here — see
    // docs/features/lut-color-grading.md. This effect only reacts to the *resolved* side:
    // CameraController.activeLut only updates once setLut has actually finished reading+parsing the LUT
    // file, so this is what genuinely drives the live preview's own LUT texture. CameraPreviewRenderer
    // is a GL object owned directly by this composable (not the ViewModel), so this collector
    // legitimately still lives here.
    val activeLut by cameraRepository.observeActiveLut().collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(activeLut) {
        renderer.setLut(activeLut?.lutId, activeLut?.cubeLut, activeLut?.intensityPercent ?: 0)
    }

    // MANUAL_SENSOR/SENSOR_INFO_SENSITIVITY_RANGE/SENSOR_INFO_EXPOSURE_TIME_RANGE and
    // CONTROL_AE_COMPENSATION_RANGE/CONTROL_AE_COMPENSATION_STEP are all per-physical-lens, not
    // per-device, so both re-query on every lens switch rather than once — see
    // CameraViewModel.manualIsoCapability/aeCompensationCapability.
    LaunchedEffect(uiState.selectedLens) {
        viewModel.onManualIsoCapabilityChanged(viewModel.manualIsoCapability(uiState.selectedLens))
        viewModel.onAeCompensationCapabilityChanged(viewModel.aeCompensationCapability(uiState.selectedLens))
        viewModel.onManualFocusCapabilityChanged(viewModel.manualFocusCapability(uiState.selectedLens))
        // REQUEST_AVAILABLE_CAPABILITIES_RAW is per-physical-lens too — same re-query-on-
        // every-lens-switch reasoning as the three capability queries above.
        viewModel.onRawCaptureCapabilityChanged(viewModel.rawCaptureCapability(uiState.selectedLens))
    }

    // Manual mode's *visible* dials appear the instant ModeToggle is tapped (manualModeEnabled), but
    // the camera itself doesn't actually lock exposure (CONTROL_AE_MODE_OFF) until 3A has genuinely
    // gone quiet (manualExposurePinned) — see docs/features/camera-capture.md for why a fixed delay
    // isn't used here. debounce restarts its quiet-window every time either live reading changes, so
    // this only proceeds once both have genuinely stopped moving; withTimeoutOrNull is a safety net in
    // case 3A never fully quiets down (e.g. flicker), so this can't stall forever. Cancels itself (via
    // LaunchedEffect's key) if manual mode is exited again before it fires.
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
            // cameraSettings.captureRawByDefault is only ever a *preference* — feature:settings has no
            // way to know whether the currently active lens actually supports RAW — so it's ANDed here
            // with the live per-lens capability check (uiState.rawCaptureSupported, from
            // CameraViewModel.rawCaptureCapability) rather than trusted on its own. This makes turning
            // the setting on while on a non-RAW lens a silent no-op (an unsupported lens is never an
            // error state) and picking up RAW automatically the moment the user switches to a lens
            // that does support it, with no extra per-shot tap.
            val includeRaw = cameraSettings.captureRawByDefault && uiState.rawCaptureSupported
            when (val result = viewModel.takePhoto(includeRaw)) {
                is Result.Success -> viewModel.onPhotoSaved(result.data)
                is Result.Error -> viewModel.onCaptureError(result.error.name)
            }
        }
    }

    // Periodically refreshes the manual-focus ring's loupe crop + focus-peaking highlight for as long
    // as the hold gesture is active — mirrors the zebra overlay's own "only pay this cost
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
                    val threshold = FocusPeakingMask.contrastThreshold(cameraSettings.focusPeakingSensitivity)
                    focusPeakingMask = withContext(Dispatchers.Default) { focusPeakingMaskFromBitmap(crop, threshold) }
                }
            }
            delay(LoupeRefreshIntervalMs)
        }
    }

    // Tap-to-focus indicator lifecycle — restarts fresh on every
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

    // Flat body fill, no grain texture — see ui/theme/CameraChrome.kt's own doc for the identity's
    // color tokens.
    Box(modifier = Modifier.fillMaxSize().background(CameraChrome.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 26.dp, end = 26.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SettingsButton(
                        onClick = { navigator.navigate(Direction(SettingsRoutes.SETTINGS_SCREEN)) },
                    )
                    FlashToggle(
                        flashOn = uiState.flashMode == FlashMode.ON,
                        onToggle = viewModel::onFlashModeToggled,
                    )
                    // Only shown once at least one LUT has been imported — mirrors ui/SettingsScreen's
                    // own luts.isNotEmpty() gating for its edit-mode toggle (see LutDial's own doc).
                    if (luts.isNotEmpty()) {
                        LutDial(
                            luts = luts,
                            selectedLutId = cameraSettings.selectedLutId,
                            onLutSelected = viewModel::onLutSelected,
                        )
                    }
                    ModeToggle(
                        manual = uiState.manualModeEnabled,
                        onToggle = viewModel::onManualModeToggled,
                    )
                }
                Seam(modifier = Modifier.padding(top = 16.dp, start = 12.dp, end = 12.dp))
            }

            // A thin black-stroke frame (CameraChrome.StrokeWidth/StrokeColor) with the flat body
            // color showing through the 9dp gap — the live camera feed itself provides the visual
            // weight this region needs.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(CameraChrome.StrokeWidth, CameraChrome.ViewfinderBezelColor, RoundedCornerShape(24.dp))
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
                            // both tap-to-focus and the hold-to-position ring are hidden/no-op together.
                            // Keyed on manualFocusSupported alone (not the whole uiState) so an
                            // in-progress gesture never gets cancelled
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
                                            // The viewfinder's own long-press only repositions the
                                            // ring/loupe — it never adjusts focus distance; that's owned
                                            // by FocusDial, in the deck row below. Uses the real touch
                                            // point directly — the ring only centers on the viewfinder
                                            // when the *dial* alone is held (see screenHoldPosition/
                                            // dialHeld's own doc above).
                                            screenHoldPosition = position
                                            focusLoupeBitmap = null
                                            focusPeakingMask = null
                                            // A hold gesture takes over from any still-visible tap
                                            // indicator so the two focus affordances never overlap.
                                            focusTapPosition = null
                                            focusTapVisible = false
                                            focusVibrator.hapticTick()
                                        },
                                        onHoldEnd = {
                                            screenHoldPosition = null
                                            focusVibrator.hapticTick()
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
                    ViewfinderGridOverlay(visible = cameraSettings.showGrid, modifier = Modifier.fillMaxSize())
                    if (cameraSettings.showHorizonLine) {
                        HorizonLineOverlay(modifier = Modifier.fillMaxSize())
                    }
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
                    LutResolvingIndicator(
                        visible = isLutResolving,
                        // fillMaxSize, not an align(...) pin — LutResolvingIndicator corner-hops
                        // internally now (TopStart reference corner, not TopEnd — HistogramOverlay
                        // corner-hops through TopEnd too, so sharing a reference corner would still let
                        // the two visually collide as both track the same rotation).
                        modifier = Modifier.fillMaxSize(),
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
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (cameraSettings.showHistogram) {
                        HistogramOverlay(
                            data = histogramData,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Seam(modifier = Modifier.padding(top = 16.dp, start = 12.dp, end = 12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CameraChrome.DeckColor)
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
                    // Only occupies a slot when the current lens actually supports manual focus — mirrors
                    // ExposureOrManualDials' own conditional-content pattern below, just at the
                    // whole-dial level rather than swapping between two dial sets.
                    // Hold-without-touching-the-screen centers the ring on the viewfinder (dialHeld,
                    // see its own doc above); rotating/dragging here is the *only* thing that actually
                    // changes the focus value — the viewfinder's own long-press only repositions the ring.
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
                                focusVibrator.hapticTick()
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
                                focusVibrator.hapticTick()
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

    // Drives CameraController's zebra-stripe analysis stream — any one of the three
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

/**
 * Section divider between the top toolbar / viewfinder / bottom deck — a flat [CameraChrome.StrokeColor]
 * hairline.
 */
@Composable
private fun Seam(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(CameraChrome.StrokeWidth).background(CameraChrome.StrokeColor))
}

// CameraPreviewRenderer computes the preview's crop/rotation transform as a GL matrix, from each
// frame's actually-delivered Image dimensions — see its own doc. There's no android.graphics.Matrix
// for this file to compute, since the live preview is never targeted directly at a
// SurfaceTexture-backed Surface.

/* ── Manual focus: tap-to-focus + hold-to-position ring gesture ───────────────────── */

/**
 * Distinguishes a simple tap (short press, negligible movement) from a long-press-and-hold (the
 * manual focus ring) on the same gesture, mirroring `DialWheel`'s own `awaitEachGesture` +
 * `awaitFirstDown` structure. A drag that moves past [viewConfigurationTouchSlopPx] before the
 * long-press threshold elapses is neither — it's ignored outright (this screen has no pan/swipe
 * gesture over the viewfinder for this to conflict with).
 *
 * While holding, this doesn't track rotation at all — that's owned by `FocusDial` instead —
 * [onHoldStart] fires once with the touch point, then this just waits for release ([onHoldEnd]),
 * consuming pointer events for the hold's whole duration so nothing else on the viewfinder can
 * interpret them meanwhile.
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
 * as a thin contour instead of boxing whole regions. [contrastThreshold] is the caller's own resolved
 * [FocusPeakingMask.contrastThreshold] for the user's current sensitivity setting, not a fixed value —
 * see that function's own doc.
 */
private fun focusPeakingMaskFromBitmap(crop: Bitmap, contrastThreshold: Int): FocusPeakingMask {
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
    return FocusPeakingMask.fromLuma(luma, width, height, columns = width, rows = height, contrastThreshold = contrastThreshold)
}
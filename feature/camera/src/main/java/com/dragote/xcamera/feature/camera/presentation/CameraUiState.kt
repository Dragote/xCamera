package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode

data class CameraUiState(
    val permissionStatus: CameraPermissionStatus = CameraPermissionStatus.Unknown,
    val isCapturing: Boolean = false,
    val lastSavedUri: Uri? = null,
    val captureError: String? = null,
    val flashMode: FlashMode = FlashMode.OFF,
    val availableLenses: List<CameraLens> = emptyList(),
    val selectedLens: CameraLens? = null,
    /** Whether [selectedLens] reports Camera2's `MANUAL_SENSOR` capability — gates manual ISO. */
    val manualIsoSupported: Boolean = false,
    /** The standard ISO ladder filtered down to [selectedLens]'s supported sensitivity range. */
    val isoStops: List<Int> = emptyList(),
    val manualModeEnabled: Boolean = false,
    val selectedIsoIndex: Int = 0,
    /** The standard shutter-speed ladder filtered down to [selectedLens]'s supported exposure-time range. */
    val shutterStops: List<Long> = emptyList(),
    val selectedShutterIndex: Int = 0,
    /**
     * True once the camera has actually locked exposure to [selectedIsoIndex]/[selectedShutterIndex]
     * (`CONTROL_AE_MODE_OFF`) — distinct from [manualModeEnabled], which flips the instant `ModeLever`
     * is tapped and only controls which dials are *shown*. See `CameraViewModel.onManualModeToggled`.
     */
    val manualExposurePinned: Boolean = false,
    /**
     * The *exact* raw sensor ISO/shutter auto-exposure last converged on (not rounded to the nearest
     * [isoStops]/[shutterStops] entry, unlike [selectedIsoIndex]/[selectedShutterIndex]) — used to pin
     * manual exposure to precisely, not approximately, whatever auto was just showing, since rounding
     * to the nearest full stop can itself be a visible brightness jump. Cleared the moment the user
     * actually drags that dial themselves (see `onIsoIndexChanged`/`onShutterIndexChanged`), at which
     * point the normal discrete-stop ladder takes back over as expected for deliberate manual dialing.
     */
    val liveAutoIso: Int? = null,
    val liveAutoExposureTimeNs: Long? = null,
    /** One entry per Camera2 AE-compensation step [selectedLens] supports; empty when unsupported. */
    val aeCompensationStops: List<Int> = emptyList(),
    /** EV value of one compensation step — needed to format [aeCompensationStops] entries for display. */
    val aeCompensationStepEv: Float = 0f,
    val selectedAeCompensationIndex: Int = 0,
    /**
     * Whether [selectedLens] reports/adjusts `LENS_FOCUS_DISTANCE` (issue #21) — gates both
     * tap-to-focus's AF-region trigger and the hold-and-rotate manual focus ring; `false` on a
     * fixed-focus lens (`LENS_INFO_MINIMUM_FOCUS_DISTANCE == 0`).
     */
    val manualFocusSupported: Boolean = false,
    /** [selectedLens]'s own `LENS_INFO_MINIMUM_FOCUS_DISTANCE` — the closest-focus end of the
     *  `[0, maxFocusDistanceDiopters]` diopter range the manual focus ring can dial through. `0f`
     *  (meaningless on its own) whenever [manualFocusSupported] is `false`. */
    val maxFocusDistanceDiopters: Float = 0f,
    /**
     * The most recent continuous-AF-converged focus distance (diopters), tracked live the same way
     * [liveAutoIso]/[liveAutoExposureTimeNs] track auto-exposure — stops updating (but keeps its last
     * value) once a manual focus hold has locked a distance, since `CameraController` itself stops
     * emitting fresh AF-converged readings while pinned (see its own `setManualFocusDistance` doc).
     * Read at the *start* of a hold gesture as the distance a rotation adjusts from.
     */
    val liveFocusDistanceDiopters: Float? = null,
    /**
     * Whether [selectedLens] reports Camera2's `RAW` capability (issue #45) — gates whether the
     * capture UI offers a "with RAW"/"without RAW" per-shot choice at all. `false` on a lens with no
     * `RAW` support (e.g. the vast majority of front/ultra-wide/tele auxiliary lenses) — matching this
     * issue's own non-goal of never surfacing RAW as an error state, just quietly not offered.
     */
    val rawCaptureSupported: Boolean = false,
    /**
     * The per-shot (not persistent-mode) "with RAW" choice — `true` means the *next* `ShutterButton`
     * tap additionally captures a `.dng`. Always forced back to `false` whenever
     * [rawCaptureSupported] itself flips to `false` (e.g. switching to a lens without RAW support) —
     * see `CameraViewModel.onRawCaptureCapabilityChanged` — so a stale "on" choice from a previous
     * lens can never silently carry over to one that doesn't support it.
     */
    val includeRawInCapture: Boolean = false,
)

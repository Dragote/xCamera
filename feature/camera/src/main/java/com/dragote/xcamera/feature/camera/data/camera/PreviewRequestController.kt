package com.dragote.xcamera.feature.camera.data.camera

import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.view.Surface
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.FocusRegionSizeFraction
import com.dragote.xcamera.feature.camera.domain.model.displayFractionToSensorFraction
import com.dragote.xcamera.shared.diagnostics.data.aeCompensationCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.data.manualFocusCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.data.manualIsoCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.domain.model.AeCompensationCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualFocusCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualIsoCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Owns the live preview's repeating-request state machine: the cached pending manual ISO/shutter/
 * focus/AF-region/AE-compensation values (tap-to-focus and the hold-and-rotate manual focus ring, the
 * manual exposure dials), building both the preview's own [CaptureRequest] ([buildPreviewRequest])
 * and — via [applyExposure]/[applyFocusSettings], also called directly by
 * [StillCaptureController.captureStillJpeg] — the exposure/focus portion of a still-capture request, and
 * the live auto-converged [autoIso]/[autoExposureTimeNs]/[autoFocusDistanceDiopters]/[afConvergenceState]
 * `StateFlow`s the ISO/shutter/focus dials observe.
 *
 * Split out of [CameraController] as "a fairly self-contained state machine around one
 * `CaptureRequest.Builder`" — [characteristicsFor] is injected as a lambda (mirroring
 * [CameraController.characteristicsFor]) rather than a back-reference to [CameraController] itself, and
 * [onRepeatingRequestNeedsRefresh] is how this tells its owner "the pending state changed, please rebuild
 * and resubmit the repeating request" without needing to hold the live `CameraDevice`/`CameraCaptureSession`/
 * `Surface` itself — those are supplied per-call instead (by [CameraController], which owns their
 * lifecycle) to [buildPreviewRequest]/[startPreviewRepeating]/[triggerAutoFocus].
 */
class PreviewRequestController(
    private val scope: CoroutineScope,
    private val characteristicsFor: (LensSnapshot?) -> CameraCharacteristics?,
    private val onRepeatingRequestNeedsRefresh: () -> Unit,
) {

    private var pendingManualIso: Int? = null
    private var pendingManualShutterNs: Long? = null

    /**
     * Non-null while a manual-focus hold gesture is active or has just released — locks
     * `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` at this exact value on both the preview's repeating
     * request and the next still capture (see [applyFocusSettings]), same "cached pending value,
     * reapplied on every request" pattern [pendingManualIso]/[pendingManualShutterNs] already use.
     * Releasing the hold does *not* clear this — only [triggerAutoFocus] (a fresh tap) does, resuming
     * continuous AF.
     */
    private var pendingManualFocusDiopters: Float? = null

    /**
     * Set by [triggerAutoFocus], applied on every subsequent request while [pendingManualFocusDiopters]
     * is `null` — Camera2's `CONTROL_AF_TRIGGER_START` only needs to be sent once (see
     * [triggerAutoFocus]'s own one-off [CameraCaptureSession.capture] call), but the *region* itself
     * needs to keep being set on every following request for continuous AF to keep tracking around it,
     * exactly like [pendingAeCompensation] being reapplied on every auto-exposure request. Reset via
     * [resetForLensSwitch] — expressed in the *previous* lens's own `SENSOR_INFO_ACTIVE_ARRAY_SIZE`
     * coordinate space, not portable across lenses.
     */
    private var pendingAfRegion: MeteringRectangle? = null

    /**
     * `true` from the moment [triggerAutoFocus] fires until the triggered scan actually settles (see
     * [captureCallback]'s own check) — while `true`, [applyFocusSettings] keeps the *repeating* request
     * in `CONTROL_AF_MODE_AUTO` too (not just the one-off trigger capture), not
     * `CONTROL_AF_MODE_CONTINUOUS_PICTURE`. `AUTO` is required for `CONTROL_AF_TRIGGER_START` to force
     * a genuine re-scan toward a newly-set `CONTROL_AF_REGIONS` on this hardware — see
     * `docs/features/camera-capture.md` for the HAL finding behind this.
     *
     * A single frame in `AUTO` mode isn't enough for the scan to actually complete, so this stays
     * `true` — keeping the *repeating* request in `AUTO` too — until [captureCallback] observes the
     * state settle into [AfConvergenceState.FOCUSED]/[AfConvergenceState.NOT_FOCUSED], at which point it
     * flips back to `false` and calls [onRepeatingRequestNeedsRefresh] once more to resume
     * `CONTINUOUS_PICTURE` for ongoing tracking. [afTriggerToken] bounds how long this can stay `true`
     * in case a HAL never reports a settled state at all.
     */
    private var pendingAfModeAuto = false

    /**
     * Incremented on every [triggerAutoFocus] call — the fallback coroutine that safety-nets
     * [pendingAfModeAuto] back to `false` (see that field's own doc) captures its own value at launch
     * and only acts if it's still current, so an old tap's fallback can't clobber a newer tap's
     * (or a hold gesture's) state after the fact.
     */
    private var afTriggerToken = 0

    /**
     * Unlike [pendingManualIso]/[pendingManualShutterNs], this has no "absent" state to represent —
     * `0` is always a valid, meaningful "no compensation" value, so this is non-null rather than
     * `Int?`. Applied only while auto-exposure (`CONTROL_AE_MODE_ON`) is active — see [applyExposure] —
     * Camera2 ignores this key entirely under `CONTROL_AE_MODE_OFF`, so there's nothing to
     * suppress/reset when manual mode is engaged.
     */
    private var pendingAeCompensation: Int = 0

    /**
     * The most recent auto-AE-converged ISO, continuously observed via this [StateFlow] rather than a
     * one-shot pull — the ISO dial reflects auto-exposure's live ISO the whole time manual mode is off
     * (see `CameraViewModel`'s collector), not just the first time it's touched. While manual mode is
     * on, the preview's repeating request instead carries a *forced* capped-manual exposure (see
     * [applyExposure]) that must **not** be mistaken for a real auto-converged value, which is exactly
     * what [captureCallback]'s [pendingManualIso]/[pendingManualShutterNs] guard prevents.
     */
    private val _autoIso = MutableStateFlow<Int?>(null)
    val autoIso: StateFlow<Int?> = _autoIso.asStateFlow()

    /**
     * Mirrors [autoIso] for shutter speed — the shutter dial tracks live auto-exposure the same way
     * the ISO dial does, not just once on first touch. Also read synchronously by [resolveManualExposure]
     * as the fallback shutter time whenever only ISO is currently pinned by manual mode (e.g. this lens
     * has no aligned shutter-speed stops), the same "starts from wherever auto last settled" behavior
     * this had before it became a [StateFlow].
     */
    private val _autoExposureTimeNs = MutableStateFlow<Long?>(null)
    val autoExposureTimeNs: StateFlow<Long?> = _autoExposureTimeNs.asStateFlow()

    /**
     * Mirrors [autoIso]/[autoExposureTimeNs] in shape (a continuously observed `StateFlow`, not a
     * one-shot pull) for `LENS_FOCUS_DISTANCE`, but *not* in update policy — unlike those two, this is
     * updated unconditionally regardless of [pendingManualFocusDiopters] (see [captureCallback]'s own
     * doc for why the ISO/shutter reasoning for gating doesn't transfer here). Reflects the most recent
     * continuous-AF-converged distance while unlocked, and the actual current lens position while a
     * manual hold has it locked — either way, always Camera2's real current reading. Used by
     * `ui/component/FocusDial`'s hold gesture as the starting point a rotation adjusts *from* (see
     * [com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation]).
     */
    private val _autoFocusDistanceDiopters = MutableStateFlow<Float?>(null)
    val autoFocusDistanceDiopters: StateFlow<Float?> = _autoFocusDistanceDiopters.asStateFlow()

    /**
     * `CaptureResult.CONTROL_AF_STATE`, translated to the domain-facing [AfConvergenceState] via
     * [afConvergenceStateFrom] — drives the tap-to-focus indicator's own lifecycle in `ui/CameraScreen`
     * (appear on tap, stay visible through [AfConvergenceState.SCANNING], hold briefly once it settles
     * into [AfConvergenceState.FOCUSED]/[AfConvergenceState.NOT_FOCUSED], then fade) rather than a
     * fixed timer pretending to know how long AF convergence takes. Updated unconditionally on every
     * capture result, unlike [_autoIso]/[_autoExposureTimeNs]/[_autoFocusDistanceDiopters] — there's no
     * "pinned/manual" value this could be mistaken for, `CONTROL_AF_STATE` is Camera2's own live status
     * regardless of AE/AF mode.
     */
    private val _afConvergenceState = MutableStateFlow<AfConvergenceState?>(null)
    val afConvergenceState: StateFlow<AfConvergenceState?> = _afConvergenceState.asStateFlow()

    /**
     * While manual mode is active ([pendingManualIso]/[pendingManualShutterNs] non-null), the
     * preview's repeating request carries a forced capped-manual exposure (see [applyExposure]), not a
     * genuine AE convergence value — so this must skip updating [_autoIso]/[_autoExposureTimeNs] in
     * that case, otherwise the ISO/shutter dials' live auto-tracking would both get fed a bogus "auto"
     * value that's actually just whatever the preview cap forced. AF is a separate axis and
     * deliberately does *not* get the same gating: [applyFocusSettings] applies [pendingManualFocusDiopters]
     * to the preview request verbatim, with no preview-only cap/rescale the way manual exposure gets, so
     * [_autoFocusDistanceDiopters] stays accurate either way and is updated unconditionally below —
     * gating it the same way ISO/shutter are would freeze `ui/component/FocusDial`'s own value readout
     * the instant a manual hold began.
     */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (pendingManualIso == null && pendingManualShutterNs == null) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { _autoExposureTimeNs.value = it }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { _autoIso.value = it }
            }
            // Unlike the ISO/shutter guard above, this stays unconditional: applyFocusSettings applies
            // pendingManualFocusDiopters to the preview request verbatim (no preview-only cap/rescale
            // the way manual exposure gets), so the result's LENS_FOCUS_DISTANCE is always the real,
            // accurate current lens position — mirroring it unconditionally is what lets FocusDial's own
            // value readout actually move while it's being dragged, instead of freezing at whatever the
            // last auto-converged reading was the instant a manual hold began.
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { _autoFocusDistanceDiopters.value = it }
            val convergence = afConvergenceStateFrom(result.get(CaptureResult.CONTROL_AF_STATE))
            _afConvergenceState.value = convergence
            // See pendingAfModeAuto's own doc — once the triggered scan actually settles, revert the
            // repeating request back to CONTINUOUS_PICTURE so AF resumes tracking rather than staying
            // frozen at whatever AUTO mode's trigger just locked.
            if (pendingAfModeAuto && (convergence == AfConvergenceState.FOCUSED || convergence == AfConvergenceState.NOT_FOCUSED)) {
                pendingAfModeAuto = false
                onRepeatingRequestNeedsRefresh()
            }
        }
    }

    /**
     * Collapses every real `CaptureResult.CONTROL_AF_STATE_*` constant into [AfConvergenceState] — see
     * that enum's own doc for why passive/active scan states fold into one [AfConvergenceState.SCANNING]
     * and passive/locked focused (or unfocused) states each fold into one settled value. `null` input
     * (device doesn't report this key at all) maps to `null` output, not a guessed default.
     */
    private fun afConvergenceStateFrom(controlAfState: Int?): AfConvergenceState? = when (controlAfState) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> AfConvergenceState.INACTIVE
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> AfConvergenceState.SCANNING
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> AfConvergenceState.FOCUSED
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AfConvergenceState.NOT_FOCUSED
        else -> null
    }

    /** `null` for [lens] queries the plain default back camera. Pure static [CameraCharacteristics]
     *  lookup, independent of whether a camera is bound yet — see [manualIsoCapabilityFrom]'s own doc. */
    fun manualIsoCapability(lens: LensSnapshot?): ManualIsoCapability? =
        characteristicsFor(lens)?.let(::manualIsoCapabilityFrom)

    /** See [aeCompensationCapabilityFrom]'s own doc. */
    fun aeCompensationCapability(lens: LensSnapshot?): AeCompensationCapability? =
        characteristicsFor(lens)?.let(::aeCompensationCapabilityFrom)

    /** See [manualFocusCapabilityFrom]'s own doc. */
    fun manualFocusCapability(lens: LensSnapshot?): ManualFocusCapability? =
        characteristicsFor(lens)?.let(::manualFocusCapabilityFrom)

    /**
     * `pendingAfRegion`/`pendingAfModeAuto` are expressed in the *previous* lens's own
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` coordinate space — not portable to a different lens, see
     * [pendingAfRegion]'s own doc. `pendingManualFocusDiopters` intentionally survives a lens switch
     * (same reasoning as [pendingManualIso]/[pendingManualShutterNs]) since diopters aren't
     * lens-active-array-relative. Called by [CameraController.bindCamera] whenever the requested lens
     * actually changes.
     */
    fun resetForLensSwitch() {
        pendingAfRegion = null
        pendingAfModeAuto = false
    }

    /** Called by [CameraController.closeCameraAndSessionLocked] on session teardown — a stale
     *  auto-converged reading (or AF state) from the closed session must not linger and be mistaken for
     *  a fresh one from whatever session opens next. */
    fun resetConvergenceState() {
        _autoExposureTimeNs.value = null
        _autoIso.value = null
        _autoFocusDistanceDiopters.value = null
        _afConvergenceState.value = null
    }

    /**
     * Starts (or restarts) the live preview repeating request against [surface], built fresh from
     * whatever [pendingManualIso]/[pendingManualShutterNs]/focus state is set to *right now* — see
     * [buildPreviewRequest]. Called once from [CameraController.openCamera] when the session is first
     * configured (so a lens switch while already in manual mode reopens with the capped-manual preview
     * active rather than silently reverting to auto), and again any time later whenever manual exposure
     * changes — `session.setRepeatingRequest` is cheap to call repeatedly and doesn't require
     * reconfiguring the session itself.
     */
    fun startPreviewRepeating(device: CameraDevice, session: CameraCaptureSession, surface: Surface, lens: LensSnapshot?, handler: Handler?) {
        session.setRepeatingRequest(buildPreviewRequest(device, surface, lens), captureCallback, handler)
    }

    /**
     * Plain auto-exposure (`CONTROL_AE_MODE_ON`) when manual mode is off. While manual mode is on,
     * this reproduces live feedback safely: `CONTROL_AE_MODE_OFF` with the *real* selected exposure
     * further capped at [PreviewMaxExposureTimeNs] so the preview frame rate never degrades, with
     * `SENSOR_SENSITIVITY`
     * boosted to compensate for the brightness the cap costs — see [applyExposure]'s own `previewSafe`
     * branch. This is a completely independent request from [StillCaptureController.captureStillJpeg]'s
     * still-capture request — the only thing shared between them is reading the same
     * [pendingManualIso]/[pendingManualShutterNs] cache, not any Camera2-level session state.
     */
    fun buildPreviewRequest(device: CameraDevice, surface: Surface, lens: LensSnapshot?): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }

        applyExposure(builder, lens, previewSafe = true)
        applyFocusSettings(builder, lens)

        return builder.build()
    }

    /**
     * Shared by [buildPreviewRequest] and [StillCaptureController.captureStillJpeg] so a manual
     * exposure choice applies identically to both, differing only in [previewSafe]: `true` (preview)
     * additionally caps the resolved shutter time to [PreviewMaxExposureTimeNs] and boosts ISO to
     * compensate for the brightness that costs (re-clamped to [capability]'s ISO range — a very long
     * selected shutter speed may not be fully compensable within the sensor's ISO ceiling, in which case
     * the live preview just runs a bit dark); `false` (still capture) uses the real, uncapped resolved
     * values, since [StillCaptureController.captureStillJpeg] is a completely independent one-off
     * request from this class's own repeating request — see that function's own doc.
     */
    fun applyExposure(builder: CaptureRequest.Builder, lens: LensSnapshot?, previewSafe: Boolean) {
        val manualCapability = if (pendingManualIso != null || pendingManualShutterNs != null) {
            manualIsoCapability(lens)
        } else {
            null
        }

        if (manualCapability != null) {
            val (iso, shutterNs) = resolveManualExposure(pendingManualIso, pendingManualShutterNs, manualCapability)
            val (finalIso, finalShutterNs) = if (previewSafe) {
                val previewShutterNs = shutterNs.coerceAtMost(PreviewMaxExposureTimeNs)
                val compensation = if (previewShutterNs > 0) shutterNs.toDouble() / previewShutterNs else 1.0
                val previewIso = (iso * compensation).roundToInt()
                    .coerceIn(manualCapability.isoRange.first, manualCapability.isoRange.last)
                previewIso to previewShutterNs
            } else {
                iso to shutterNs
            }

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, finalIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, finalShutterNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, pendingAeCompensation)
        }
    }

    /**
     * AF is an independent axis from AE (see [applyExposure]) — tap-to-focus and the hold-and-rotate
     * manual focus ring both drive `CONTROL_AF_MODE`/`LENS_FOCUS_DISTANCE`/
     * `CONTROL_AF_REGIONS`, orthogonal to whichever ISO/shutter mode is active. Shared between
     * [buildPreviewRequest] and [StillCaptureController.captureStillJpeg] so a locked manual focus
     * distance (or an active tap-to-focus region) applies identically to both the live preview and the
     * actual capture, the same "one cache, reapplied to every request" pattern
     * [pendingManualIso]/[pendingManualShutterNs] already use for exposure. A no-op (falls through to
     * the request template's own AF default) on a lens with no [manualFocusCapability] — i.e. a
     * fixed-focus lens, where there's no `LENS_FOCUS_DISTANCE` control surface to touch at all.
     */
    fun applyFocusSettings(builder: CaptureRequest.Builder, lens: LensSnapshot?) {
        val focusCapability = manualFocusCapability(lens) ?: return

        val manualDistance = pendingManualFocusDiopters
        if (manualDistance != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                manualDistance.coerceIn(0f, focusCapability.maxFocusDistanceDiopters),
            )
        } else {
            // See pendingAfModeAuto's own doc for why a triggered scan has to stay in AUTO (not
            // CONTINUOUS_PICTURE) for more than just the one-off trigger frame on this hardware.
            val afMode = if (pendingAfModeAuto) {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            pendingAfRegion?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        }
    }

    /**
     * Standard Camera2 tap-to-focus: [displayXFraction]/[displayYFraction] (`0f..1f`, top-left origin)
     * are a tap point expressed as a fraction of the *displayed* viewfinder — converted into the
     * sensor's own active-array coordinate space via [displayFractionToSensorFraction] (see that
     * function's own doc for the approximation it makes), then built into a [MeteringRectangle]
     * centered on the tap ([FocusRegionSizeFraction] of the active array's own width/height) and pushed
     * as `CONTROL_AF_REGIONS` alongside a one-off `CONTROL_AF_TRIGGER_START` capture, both in
     * `CONTROL_AF_MODE_AUTO` — **not** `CONTINUOUS_PICTURE` — see [pendingAfModeAuto]'s own doc for why
     * `AUTO` is required here for a real lens movement on this hardware. Always clears
     * [pendingManualFocusDiopters] first — a tap always resumes AF, overriding whatever manual focus
     * lock a previous hold gesture may have left in place. A no-op on a lens with no
     * [manualFocusCapability], or before a session is actually open (nothing to focus yet — [device]/
     * [session]/[surface] all supplied by [CameraController], which is the one that knows whether a
     * session is currently open).
     */
    fun triggerAutoFocus(
        displayXFraction: Float,
        displayYFraction: Float,
        device: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface,
        lens: LensSnapshot?,
        rotationDegrees: Int,
        handler: Handler?,
    ) {
        val characteristics = characteristicsFor(lens) ?: return
        manualFocusCapability(lens) ?: return
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        pendingManualFocusDiopters = null
        pendingAfModeAuto = true
        val thisTriggerToken = ++afTriggerToken

        val (sensorXFraction, sensorYFraction) =
            displayFractionToSensorFraction(displayXFraction, displayYFraction, rotationDegrees)
        val region = afRegionAround(sensorXFraction, sensorYFraction, activeArray)
        pendingAfRegion = region

        try {
            val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()
            session.capture(triggerRequest, null, handler)
        } catch (e: CameraAccessException) {
            // Session/device is mid-teardown — nothing to recover, the next bind starts fresh.
        } catch (e: IllegalStateException) {
            // Session already closed — same as above.
        }

        // The repeating request also needs to carry AF_MODE_AUTO (not just this one-off trigger frame)
        // for the scan to actually have time to complete — see pendingAfModeAuto's own doc. Reverted
        // back to CONTINUOUS_PICTURE once captureCallback observes the scan settle, or by the fallback
        // below if that never happens.
        onRepeatingRequestNeedsRefresh()

        scope.launch {
            delay(AfAutoModeFallbackTimeoutMs)
            // Only acts if this is still the most recent trigger and it's still stuck in AUTO — an
            // already-settled (or superseded by a newer tap/hold) trigger has nothing to fall back on.
            if (pendingAfModeAuto && afTriggerToken == thisTriggerToken) {
                pendingAfModeAuto = false
                onRepeatingRequestNeedsRefresh()
            }
        }
    }

    /** [FocusRegionSizeFraction] of the active array's own width/height, centered on
     *  ([centerXFraction], [centerYFraction]) and clamped within the array's bounds. */
    private fun afRegionAround(centerXFraction: Float, centerYFraction: Float, activeArray: Rect): MeteringRectangle {
        val regionWidth = (activeArray.width() * FocusRegionSizeFraction).roundToInt().coerceAtLeast(1)
        val regionHeight = (activeArray.height() * FocusRegionSizeFraction).roundToInt().coerceAtLeast(1)
        val centerX = (activeArray.left + centerXFraction * activeArray.width()).roundToInt()
        val centerY = (activeArray.top + centerYFraction * activeArray.height()).roundToInt()
        val left = (centerX - regionWidth / 2).coerceIn(activeArray.left, (activeArray.right - 1).coerceAtLeast(activeArray.left))
        val top = (centerY - regionHeight / 2).coerceIn(activeArray.top, (activeArray.bottom - 1).coerceAtLeast(activeArray.top))
        val right = (left + regionWidth).coerceAtMost(activeArray.right)
        val bottom = (top + regionHeight).coerceAtMost(activeArray.bottom)
        return MeteringRectangle(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /**
     * Drives the hold-and-rotate manual focus ring: [distanceDiopters] non-`null` locks
     * `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` at that value on both the preview's repeating
     * request and the next still capture (see [applyFocusSettings]) — called on every rotation tick
     * while the ring is held, and left at whatever value it was last called with once the finger
     * releases (this function itself is never called with `null` on release — "commit/lock" is simply
     * *not clearing* [pendingManualFocusDiopters], the same way manual exposure has no separate
     * "commit" step beyond having already set the pending value). `null` resumes continuous
     * AF — only [triggerAutoFocus] (a fresh tap) does that today. Immediately live-updates the preview's
     * repeating request via [onRepeatingRequestNeedsRefresh], mirroring [setManualExposure].
     */
    fun setManualFocusDistance(distanceDiopters: Float?) {
        pendingManualFocusDiopters = distanceDiopters
        onRepeatingRequestNeedsRefresh()
    }

    /**
     * Both `null` means the next still capture runs plain auto-exposure; either non-null fixes both
     * ISO and shutter speed together on that capture — per Camera2, `CONTROL_AE_MODE_OFF` fixes ISO
     * *and* exposure time simultaneously, there's no "ISO manual, shutter auto" mode (or vice versa).
     * Cached in [pendingManualIso]/[pendingManualShutterNs] so it's reapplied to the next still capture
     * regardless of lens rebinds in between. Also immediately live-updates the preview's repeating
     * request via [onRepeatingRequestNeedsRefresh] (a no-op if no session is open yet) so the viewfinder
     * visually reflects every dial tick, capped to a preview-safe exposure time — see [applyExposure].
     * This only ever touches the preview's own repeating request;
     * [StillCaptureController.captureStillJpeg]'s still-capture request is built completely
     * independently and is never affected by it.
     */
    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) {
        pendingManualIso = iso
        pendingManualShutterNs = shutterTimeNs
        onRepeatingRequestNeedsRefresh()
    }

    /**
     * Cached in [pendingAeCompensation] the same way manual exposure is cached above, and immediately
     * live-updates the preview's repeating request via [onRepeatingRequestNeedsRefresh] so the
     * viewfinder reflects every EXPOSURE-dial tick. Harmless to call while manual mode is active —
     * [applyExposure] only ever applies this in its `CONTROL_AE_MODE_ON` branch, so it's simply unused
     * (not cleared/reset) until auto-exposure is active again.
     */
    fun setExposureCompensation(value: Int) {
        pendingAeCompensation = value
        onRepeatingRequestNeedsRefresh()
    }

    /**
     * Resolves [iso]/[shutterTimeNs]: whichever is null (i.e. that parameter's stop list is currently
     * empty for this lens, so its own dial has nothing to pin — both dials are always visible now, but
     * an unsupported/unaligned range can still leave one of them without a value to contribute) falls
     * back to [_autoExposureTimeNs]'s current value for shutter or the range floor for ISO, then both
     * are clamped to [capability]'s supported sensor range, so engaging manual mode for one parameter
     * doesn't itself cause a brightness jump from whatever the other was left at.
     */
    private fun resolveManualExposure(iso: Int?, shutterTimeNs: Long?, capability: ManualIsoCapability): Pair<Int, Long> {
        val clampedIso = (iso ?: capability.isoRange.first)
            .coerceIn(capability.isoRange.first, capability.isoRange.last)
        val clampedShutterNs = (shutterTimeNs ?: _autoExposureTimeNs.value ?: capability.exposureTimeRange.first)
            .coerceIn(capability.exposureTimeRange.first, capability.exposureTimeRange.last)
        return clampedIso to clampedShutterNs
    }

    private companion object {
        /**
         * Ceiling on the exposure time pushed to the *live preview's* repeating request while manual
         * mode is active, regardless of how long a shutter speed the user has actually dragged to — see
         * [applyExposure]. Once `CONTROL_AE_MODE_OFF` is set, each preview frame's duration *is* the
         * configured `SENSOR_EXPOSURE_TIME`; pushing an 8s exposure straight to the repeating request
         * would drop the viewfinder to ~0.125fps and leave it visibly frozen, since the sensor has to
         * finish reading out whatever long-exposure frames were already queued before a fresh fast one
         * can land. 1/15s keeps manual-mode preview comfortably fluid (a frame rate a dim-light
         * *auto*-exposure preview already commonly runs at) while still long enough that the
         * `SENSOR_SENSITIVITY` compensation needed to match brightness rarely needs to leave a flagship
         * sensor's usable ISO range. This only ever affects the preview's own repeating request — the
         * still capture in [StillCaptureController.captureStillJpeg] always uses the real, uncapped
         * selected shutter speed, with no shared Camera2-level state between the two requests.
         */
        const val PreviewMaxExposureTimeNs = 1_000_000_000L / 15

        /** Bounded safety net for [pendingAfModeAuto] — see that field's own doc. Generous relative to
         *  how fast a triggered AF scan actually settles on real hardware, just there so a HAL that
         *  never reports a settled `CONTROL_AF_STATE` can't leave the repeating request stuck in
         *  `CONTROL_AF_MODE_AUTO` (frozen focus, no continuous tracking) forever. */
        const val AfAutoModeFallbackTimeoutMs = 2_000L
    }
}

package com.dragote.xcamera.feature.camera.domain.model

import com.dragote.xcamera.shared.common.domain.model.CubeLut

/**
 * [CubeLut] (`shared:common`, moved there in issue #43's follow-up so `feature:settings` can share
 * the same parse/resample logic) plus the blend intensity to mix it in at — the one thing
 * `ui/CameraScreen`'s preview renderer and `CameraController`'s still-capture path both need, cached
 * together in `CameraController.activeLut` the same "single pending value, reapplied everywhere it's
 * needed" way `pendingManualIso`/`pendingManualShutterNs` already are.
 *
 * [lutId] (issue #43's quick-access-dial follow-up) is the originating
 * [com.dragote.xcamera.shared.common.domain.model.LutPreset.id] [cubeLut] was resolved from —
 * threaded through purely so `ui/gl/CameraPreviewRenderer`'s GPU texture cache can key already-
 * uploaded textures by this stable id instead of re-uploading on every switch between LUTs it's
 * already seen this session (every [CubeLut] is now the same canonical grid size after issue #43's
 * resample-at-import-time change, so texture *dimensions* never vary between different LUTs any
 * more — see that renderer's own doc).
 */
data class ActiveLut(val lutId: String, val cubeLut: CubeLut, val intensityPercent: Int)

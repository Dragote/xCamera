package com.dragote.xcamera.feature.camera.domain.model

import com.dragote.xcamera.shared.common.domain.model.CubeLut

/** [CubeLut] (`shared:common`, moved there in issue #43's follow-up so `feature:settings` can share
 *  the same parse/resample logic) plus the blend intensity to mix it in at — the one thing
 *  `ui/CameraScreen`'s preview renderer and `CameraController`'s still-capture path both need, cached
 *  together in `CameraController.activeLut` the same "single pending value, reapplied everywhere it's
 *  needed" way `pendingManualIso`/`pendingManualShutterNs` already are. */
data class ActiveLut(val cubeLut: CubeLut, val intensityPercent: Int)

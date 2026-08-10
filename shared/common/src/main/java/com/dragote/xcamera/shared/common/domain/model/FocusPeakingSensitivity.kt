package com.dragote.xcamera.shared.common.domain.model

/**
 * User-configurable focus-peaking trigger sensitivity, persisted by `feature:settings` alongside
 * the rest of [CameraSettings] and consumed by `feature:camera` — mirrors real cameras' own
 * Low/Mid/High peaking-sensitivity setting (Sony/Panasonic). Maps to `feature:camera`'s
 * `FocusPeakingMask.contrastThreshold`: a *higher* sensitivity flags weaker/softer edges as "sharp,"
 * which in practice means the highlight triggers earlier during a manual-focus rack — sensitivity is
 * inversely related to the actual luma-contrast floor a cell must clear. [LOW] trades a later trigger
 * (you have to get closer to true peak focus before anything lights up) for fewer premature "that's
 * sharp enough" reads, which is what pushed this setting to exist in the first place.
 */
enum class FocusPeakingSensitivity { LOW, MEDIUM, HIGH }

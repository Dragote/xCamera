package com.dragote.xcamera.shared.common.domain.model

/**
 * The one hue the user is allowed to introduce into an otherwise monochrome chrome, persisted by
 * `feature:settings` alongside the rest of [CameraSettings]. [OFF] is the default and means exactly
 * what it says — no hue at all, every control drawn in `MinimalChrome`'s ink as before.
 *
 * The three hues are fixed values that deliberately do **not** follow `INVERT CHROME`: an accent
 * whose hue shifted with the body would stop being "the user's color." Each is picked to read
 * against both the paper-white and the near-black body, since either can be live under it.
 *
 * [argb] carries the actual color because `shared:designsystem` — the source of truth for every
 * *other* chrome token — can't be reached from both sides of this: it depends on no other module, so
 * it can't name this enum, while `feature:camera` (which paints with the hue) and `feature:settings`
 * (which renders the options in their own colors) both need the values. A plain `Long` keeps this
 * model free of Android/Compose types; the alternative — a mirrored designsystem enum — buys a
 * cleaner layer at the cost of a mapping `when` duplicated in both features.
 */
enum class AccentColor(val argb: Long?) {
    /** No accent — chrome stays monochrome, and the shutter button follows `INVERT CHROME` as it did
     *  before this preference existed. */
    OFF(argb = null),
    ORANGE(argb = 0xFFE86A1C),
    BLUE(argb = 0xFF3A6FE0),
    GREEN(argb = 0xFF2F9E5B),
}

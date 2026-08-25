package com.dragote.xcamera.shared.diagnostics.domain.model

/**
 * One physical back lens's characteristics-only diagnostics report. [displayLabel] and each
 * [FeatureSupport] entry below are derived once, in `data/mapper/LensCandidateMapper.kt`, from
 * [snapshot]'s raw characteristics — `ui/component/LensDiagnosticsCard.kt` only formats already-derived
 * data, it never re-reads `CameraCharacteristics` itself.
 */
data class LensDiagnostics(
    /** E.g. "0.5× ULTRA-WIDE" / "1× MAIN" / "3× TELEPHOTO" — see
     *  `LensCandidateMapper.toDisplayLabel`. */
    val displayLabel: String,
    val snapshot: LensSnapshot,
    val rawCapture: FeatureSupport,
    val manualIsoAndShutter: FeatureSupport,
    val manualFocus: FeatureSupport,
)

/**
 * Whether a lens supports a given capability, with a human-readable [Supported.detail] string
 * (e.g. "12 MP RAW", "ISO 50–3200", "down to 10 cm") when it does — mirrors
 * `shared:diagnostics`'s own "presence of a typed capability, not a boolean flag" pattern, one level
 * up: here the type itself always exists, but only [Supported] carries a value worth showing.
 */
sealed interface FeatureSupport {
    data class Supported(val detail: String) : FeatureSupport
    data object Unsupported : FeatureSupport
}

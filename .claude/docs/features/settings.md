# Settings

**Purpose:** `feature:settings` owns persisted camera preferences and the imported-LUT library — the backing store and screen behind `feature:camera`'s viewfinder toggles and LUT picker, reached via the `SettingsRoutes.SETTINGS_SCREEN` route constant (`shared:navigation`) rather than a cross-feature dependency.

**Current state:**
- `CameraSettingsLocalDataSource` wraps a `DataStore<Preferences>` as the sole owner of the actual preference keys: `showGrid`, `showHistogram`, `showHorizonLine`, `focusPeakingSensitivity` (enum, corrupt/unknown stored value falls back to default rather than crashing), `selectedLutId`/`lutIntensityPercent`, `captureRawByDefault`. A corrupt preferences file is caught and read as empty (all defaults) rather than propagating a failure — a settings read has no meaningful failure mode a caller could act on. `CameraSettingsRepositoryImpl` exposes this as `Flow<CameraSettings>` (`shared:common` domain model) plus per-field setters.
- `LutLocalDataSource` owns the imported-LUT library on app-private storage (`filesDir/luts/`): one binary file per import (`CubeLut.toBinary`, id + display name encoded in the file name), no separate metadata DB — `listLuts()` is a directory scan. `importLut` validates + parses (`parseCubeLut`, `shared:common`), resamples to the canonical 33³ grid, and writes only the resampled binary form; the picked file's raw bytes are never persisted, so a failed validation leaves nothing to clean up. `LutRepositoryImpl` wraps this as `Result`-returning list/import/delete.
- `SettingsScreen` (`ui/`): GRID/HISTOGRAM/HORIZON/RAW-CAPTURE lever toggles, a focus-peaking sensitivity picker, and a `LutSelector` (chip list, SAF import launcher, intensity slider, jiggle-mode deletion, per-chip resolving spinner). `SettingsViewModel` drives `SettingsUiState` from both repositories.
- See `camera-capture.md` (RAW capture preference) and `lut-color-grading.md` (LUT import/selection/grading) for how `feature:camera` consumes what this module persists.

**Key decisions:**
- `feature:camera` reads/writes these settings only through `shared:common`'s `CameraSettings`/`CameraSettingsRepository`/`LutRepository` contracts, never a direct `feature:settings` dependency — feature modules never depend on each other directly (root `CLAUDE.md`).
- LUT library storage is a plain scanned directory, not Room — per this project's minimal-infra preference, the smallest working slice for a first import/list/delete store, not a placeholder for a "real" database.
- `CubeLut` parse/resample/binary-format logic lives in `shared:common`, not duplicated here, so this module's import-time validation and `feature:camera`'s runtime read share one implementation.

**Open questions:**
- None currently open specific to this module — see `lut-color-grading.md`'s and `camera-capture.md`'s own open questions for gaps in the features this module backs.

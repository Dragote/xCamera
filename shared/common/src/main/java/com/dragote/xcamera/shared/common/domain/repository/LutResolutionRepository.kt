package com.dragote.xcamera.shared.common.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The third cross-feature domain contract this app needed (after [CameraSettingsRepository] and
 * [LutRepository]) — `feature:camera` owns the implementation (tracked around
 * `feature.camera.domain.repository.CameraRepository.setLut`'s own file-read/parse work),
 * `feature:settings` consumes it to know when a LUT selection it just persisted via
 * [CameraSettingsRepository.setSelectedLutId] is still being resolved on the camera side, so it can
 * show a loading spinner on that specific LUT's chip rather than nothing. Neither feature module
 * depends on the other directly — this exists precisely because `feature:settings` can't see
 * `CameraRepository.observeActiveLut()`.
 */
interface LutResolutionRepository {

    /**
     * The `LutPreset.id` currently being resolved (file read + `.cube` parse) by
     * `CameraRepository.setLut`, or `null` when nothing is in flight. Never emits the `lutId = null`
     * ("turn grading off") case — clearing the active LUT is synchronous, there's nothing to show a
     * spinner for.
     */
    fun observeResolvingLutId(): Flow<String?>

    /**
     * Emits a `LutPreset.id` once whenever `CameraRepository.setLut` was asked to resolve a *non-null*
     * `lutId` (an actual selection, not the "OFF" `null` path — see [observeResolvingLutId]'s own doc
     * for why that path never has anything to fail) but ended up with a `null` parsed LUT — whether
     * because [lutId] wasn't found in the LUT list at all, its file couldn't be read, or its content
     * failed `.cube` parsing. A one-shot event stream, not sticky state the way [observeResolvingLutId]
     * is (a `MutableSharedFlow`-backed implementation, not a `StateFlow`) — a failure should surface
     * exactly once to whoever's collecting (`feature:settings`' `SettingsViewModel`, to auto-clean-up
     * the broken import and surface a toast), not linger as state a later, unrelated collector would
     * replay.
     */
    fun observeResolutionFailures(): Flow<String>
}

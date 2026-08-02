package com.dragote.xcamera.feature.camera.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher around each test — needed now that
 * `CameraViewModel` launches a `viewModelScope` collector in `init` (see its `observeAutoIso`
 * collection), which otherwise fails outside an Android/instrumented environment. Duplicated here
 * rather than shared per this module's testing convention (extract to `shared:testing` only once a
 * second feature module needs the same rule). Defaults to [UnconfinedTestDispatcher] (not
 * [kotlinx.coroutines.test.StandardTestDispatcher]) so a `viewModelScope.launch` started from
 * `init` runs eagerly up to its first suspension point instead of needing an explicit
 * `advanceUntilIdle()` before assertions on `CameraViewModel`'s very first emitted `uiState`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

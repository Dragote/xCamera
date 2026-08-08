package com.dragote.xcamera.shared.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher around each test — needed by any ViewModel that
 * launches a `viewModelScope` collector in `init`, which otherwise fails outside an Android/
 * instrumented environment. Extracted here (out of `feature:camera`) once `feature:settings`
 * became the second feature module needing it, per this project's testing convention. Defaults to
 * [UnconfinedTestDispatcher] (not [kotlinx.coroutines.test.StandardTestDispatcher]) so a
 * `viewModelScope.launch` started from `init` runs eagerly up to its first suspension point instead
 * of needing an explicit `advanceUntilIdle()` before assertions on a ViewModel's very first emitted
 * UI state.
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

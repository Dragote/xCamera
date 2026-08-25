package com.dragote.xcamera.shared.navigation

/**
 * Route constant `feature:settings` navigates to `feature:diagnostics` with, mirroring
 * [SettingsRoutes]'s own doc — feature modules never depend on each other directly, so
 * `feature:diagnostics` puts `@Destination(route = DiagnosticsRoutes.DIAGNOSTICS_SCREEN)` on its
 * screen composable and `feature:settings` navigates to it by this route rather than a generated
 * `DiagnosticsScreenDestination`.
 */
object DiagnosticsRoutes {
    const val DIAGNOSTICS_SCREEN = "diagnostics_screen"
}

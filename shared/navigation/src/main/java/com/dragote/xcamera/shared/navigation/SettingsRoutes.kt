package com.dragote.xcamera.shared.navigation

/**
 * Route constants shared between `feature:camera` and `feature:settings` so one can navigate to
 * the other without a direct Gradle dependency between them (feature modules never depend on each
 * other directly — see root `CLAUDE.md`). `feature:settings` puts `@Destination(route =
 * SettingsRoutes.SETTINGS_SCREEN)` on its screen composable; `feature:camera` navigates to it with
 * `navigator.navigate(route = SettingsRoutes.SETTINGS_SCREEN)`.
 */
object SettingsRoutes {
    const val SETTINGS_SCREEN = "settings_screen"
}

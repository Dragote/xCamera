package com.dragote.xcamera.navigation

import com.dragote.xcamera.feature.camera.ui.cameraDestinations
import com.dragote.xcamera.feature.camera.ui.destinations.CameraScreenDestination
import com.dragote.xcamera.feature.settings.ui.settingsDestinations
import com.ramcosta.composedestinations.spec.DestinationSpec
import com.ramcosta.composedestinations.spec.NavGraphSpec
import com.ramcosta.composedestinations.spec.Route

/**
 * Hand-assembled root nav graph: each feature module is built with the compose-destinations
 * KSP option `mode = "destinations"` (see xcamera.compose.destinations convention plugin), which
 * generates a flat list of destinations per module (e.g. [cameraDestinations]) instead of its own
 * NavGraphs object. Adding a new feature means adding its `<module>Destinations` list here.
 */
object AppNavGraph : NavGraphSpec {
    override val route: String = "root"
    override val baseRoute: String = route
    override val startRoute: Route = CameraScreenDestination
    override val destinationsByRoute: Map<String, DestinationSpec<*>> =
        (cameraDestinations + settingsDestinations).associateBy { it.route }
    override val nestedNavGraphs: List<NavGraphSpec> = emptyList()
}

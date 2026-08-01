package com.dragote.xcamera.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("xcamera.android.library")
            pluginManager.apply("xcamera.android.library.compose")
            pluginManager.apply("xcamera.android.hilt")
            pluginManager.apply("xcamera.compose.destinations")

            dependencies.add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            dependencies.add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            dependencies.add("implementation", libs.findLibrary("hilt-navigationCompose").get())
        }
    }
}

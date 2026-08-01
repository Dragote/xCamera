package com.dragote.xcamera.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("xcamera.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<LibraryExtension> {
                buildFeatures.compose = true
            }

            val bom = libs.findLibrary("androidx-compose-bom").get()
            dependencies.add("implementation", dependencies.platform(bom))
            dependencies.add("androidTestImplementation", dependencies.platform(bom))
            dependencies.add("implementation", libs.findLibrary("androidx-ui").get())
            dependencies.add("implementation", libs.findLibrary("androidx-ui-graphics").get())
            dependencies.add("implementation", libs.findLibrary("androidx-ui-tooling-preview").get())
            dependencies.add("implementation", libs.findLibrary("androidx-material3").get())
            dependencies.add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            dependencies.add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            dependencies.add("debugImplementation", libs.findLibrary("androidx-ui-tooling").get())
        }
    }
}

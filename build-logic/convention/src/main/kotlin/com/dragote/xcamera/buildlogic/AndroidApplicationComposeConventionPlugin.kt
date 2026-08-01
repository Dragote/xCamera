package com.dragote.xcamera.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("xcamera.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<ApplicationExtension> {
                buildFeatures.compose = true
            }

            val bom = libs.findLibrary("androidx-compose-bom").get()
            dependencies.add("implementation", dependencies.platform(bom))
            dependencies.add("androidTestImplementation", dependencies.platform(bom))
            dependencies.add("implementation", libs.findLibrary("androidx-ui").get())
            dependencies.add("implementation", libs.findLibrary("androidx-ui-graphics").get())
            dependencies.add("implementation", libs.findLibrary("androidx-ui-tooling-preview").get())
            dependencies.add("implementation", libs.findLibrary("androidx-material3").get())
            dependencies.add("debugImplementation", libs.findLibrary("androidx-ui-tooling").get())
            dependencies.add("debugImplementation", libs.findLibrary("androidx-ui-test-manifest").get())
            dependencies.add("androidTestImplementation", libs.findLibrary("androidx-ui-test-junit4").get())
        }
    }
}

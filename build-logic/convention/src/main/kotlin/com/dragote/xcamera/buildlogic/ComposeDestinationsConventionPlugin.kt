package com.dragote.xcamera.buildlogic

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class ComposeDestinationsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            dependencies.add("implementation", libs.findLibrary("composeDestinations-core").get())
            dependencies.add("ksp", libs.findLibrary("composeDestinations-ksp").get())

            extensions.configure<KspExtension> {
                arg("compose-destinations.mode", "destinations")
                arg("compose-destinations.moduleName", target.name)
            }
        }
    }
}

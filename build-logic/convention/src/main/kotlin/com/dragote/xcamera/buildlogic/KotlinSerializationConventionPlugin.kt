package com.dragote.xcamera.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class KotlinSerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            dependencies.add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
            dependencies.add("implementation", libs.findLibrary("retrofit-core").get())
            dependencies.add("implementation", libs.findLibrary("retrofit-kotlinxSerializationConverter").get())
            dependencies.add("implementation", libs.findLibrary("okhttp-core").get())
            dependencies.add("implementation", libs.findLibrary("okhttp-loggingInterceptor").get())
        }
    }
}

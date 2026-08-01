package com.dragote.xcamera.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            dependencies.add("implementation", libs.findLibrary("room-runtime").get())
            dependencies.add("implementation", libs.findLibrary("room-ktx").get())
            dependencies.add("ksp", libs.findLibrary("room-compiler").get())
        }
    }
}

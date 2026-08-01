package com.dragote.xcamera.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = 36

        defaultConfig {
            minSdk = 26
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    dependencies.add("testImplementation", libs.findLibrary("junit").get())
    dependencies.add("testImplementation", libs.findLibrary("mockk").get())
    dependencies.add("testImplementation", libs.findLibrary("turbine").get())
    dependencies.add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    dependencies.add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
    dependencies.add("androidTestImplementation", libs.findLibrary("androidx-espresso-core").get())
}

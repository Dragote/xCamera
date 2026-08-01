plugins {
    `kotlin-dsl`
}

group = "com.dragote.xcamera.buildlogic"

dependencies {
    implementation(libs.gradlePlugin.android)
    implementation(libs.gradlePlugin.kotlin)
    implementation(libs.gradlePlugin.composeCompiler)
    implementation(libs.gradlePlugin.kotlinSerialization)
    implementation(libs.gradlePlugin.ksp)
    implementation(libs.gradlePlugin.hilt)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "xcamera.android.application"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "xcamera.android.application.compose"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "xcamera.android.library"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "xcamera.android.library.compose"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "xcamera.android.hilt"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "xcamera.android.room"
            implementationClass = "com.dragote.xcamera.buildlogic.AndroidRoomConventionPlugin"
        }
        register("composeDestinations") {
            id = "xcamera.compose.destinations"
            implementationClass = "com.dragote.xcamera.buildlogic.ComposeDestinationsConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "xcamera.kotlin.serialization"
            implementationClass = "com.dragote.xcamera.buildlogic.KotlinSerializationConventionPlugin"
        }
        register("feature") {
            id = "xcamera.feature"
            implementationClass = "com.dragote.xcamera.buildlogic.FeatureConventionPlugin"
        }
    }
}

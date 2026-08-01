plugins {
    id("xcamera.feature")
}

android {
    namespace = "com.dragote.xcamera.feature.camera"
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}

plugins {
    id("xcamera.feature")
}

android {
    namespace = "com.dragote.xcamera.feature.camera"
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))
    implementation(project(":shared:navigation"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.material.icons.extended)

    testImplementation(project(":shared:testing"))
}

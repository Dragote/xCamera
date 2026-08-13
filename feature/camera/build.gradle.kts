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

    testImplementation(project(":shared:testing"))
}

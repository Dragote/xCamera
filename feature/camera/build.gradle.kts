plugins {
    id("xcamera.feature")
}

android {
    namespace = "com.dragote.xcamera.feature.camera"
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))
}

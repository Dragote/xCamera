plugins {
    id("xcamera.android.library")
    id("xcamera.android.hilt")
}

android {
    namespace = "com.dragote.xcamera.shared.diagnostics"
}

dependencies {
    implementation(project(":shared:common"))

    testImplementation(project(":shared:testing"))
}

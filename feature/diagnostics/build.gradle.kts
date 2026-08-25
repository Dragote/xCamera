plugins {
    id("xcamera.feature")
}

android {
    namespace = "com.dragote.xcamera.feature.diagnostics"
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))
    implementation(project(":shared:navigation"))
    implementation(project(":shared:diagnostics"))

    testImplementation(project(":shared:testing"))
}

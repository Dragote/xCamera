plugins {
    id("xcamera.feature")
}

android {
    namespace = "com.dragote.xcamera.feature.settings"
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))
    implementation(project(":shared:navigation"))
    implementation(libs.androidx.datastore.preferences)

    testImplementation(project(":shared:testing"))
}

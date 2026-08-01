plugins {
    id("xcamera.android.library")
    id("xcamera.android.hilt")
}

android {
    namespace = "com.dragote.xcamera.shared.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.loggingInterceptor)
    implementation(libs.kotlinx.serialization.json)
}

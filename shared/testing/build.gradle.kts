plugins {
    id("xcamera.android.library")
}

android {
    namespace = "com.dragote.xcamera.shared.testing"
}

dependencies {
    // api, not implementation — consumers pull this in via testImplementation(project(":shared:testing"))
    // and need TestDispatcher/TestWatcher types transitively.
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}

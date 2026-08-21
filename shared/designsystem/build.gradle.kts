plugins {
    id("xcamera.android.library")
    id("xcamera.android.library.compose")
}

android {
    namespace = "com.dragote.xcamera.shared.designsystem"
}

dependencies {
    // Only pulled in for Toggle.kt's own @Preview (Icons.Default.FlashOn isn't in material3's bundled
    // icon set) — Toggle's real icon param is caller-supplied, so this isn't a hard runtime dependency
    // for consumers, just for this module's own preview to compile.
    implementation(libs.androidx.material.icons.extended)
}

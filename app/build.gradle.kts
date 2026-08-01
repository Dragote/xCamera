plugins {
    id("xcamera.android.application")
    id("xcamera.android.application.compose")
    id("xcamera.android.hilt")
}

android {
    namespace = "com.dragote.xcamera"

    defaultConfig {
        applicationId = "com.dragote.xcamera"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:designsystem"))
    implementation(project(":feature:camera"))
    implementation(libs.composeDestinations.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}

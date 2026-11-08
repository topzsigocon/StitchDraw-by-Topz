plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.topz.stitchdraw.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.topz.stitchdraw"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":workspace"))
    implementation(project(":file-engine"))
    implementation(project(":export-engine"))
    implementation(project(":digitizing-engine"))
    implementation(project(":rendering-engine"))
}

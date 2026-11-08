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
        // Every CI run gets a higher version, so a new APK always installs over the old one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "0.2.$run"
    }

    // ONE fixed signing key for every build. Android only installs an update over an installed app when the
    // signature matches; the GitHub runner would otherwise create a brand-new random debug key on each run,
    // which is why updates used to require uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("stitchdraw.keystore")
            storePassword = "stitchdraw-by-topz"
            keyAlias = "stitchdraw"
            keyPassword = "stitchdraw-by-topz"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

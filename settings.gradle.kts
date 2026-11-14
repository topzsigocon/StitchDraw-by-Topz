pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StitchDraw"

// Pure Kotlin/JVM engines (unit-testable without Android)
include(":core")
include(":vector-engine")
include(":vector-art-engine")
include(":embroidery-engine")
include(":digitizing-engine")
include(":workspace")
include(":file-engine")
include(":export-engine")
// Android modules
include(":rendering-engine")
include(":app")

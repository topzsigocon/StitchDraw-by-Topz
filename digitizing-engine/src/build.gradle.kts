plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":embroidery-engine"))
    testImplementation(libs.junit)
    // Test-only: proves the workspace can never disturb a digitizing session.
    testImplementation(project(":workspace"))
}

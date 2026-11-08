plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":vector-engine"))
    testImplementation(libs.junit)
}

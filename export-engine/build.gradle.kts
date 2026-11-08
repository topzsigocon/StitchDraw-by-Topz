plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":embroidery-engine"))
    testImplementation(libs.junit)
}

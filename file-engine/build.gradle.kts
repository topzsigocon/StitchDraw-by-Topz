plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":embroidery-engine"))
    api(project(":core"))
    testImplementation(libs.junit)
}

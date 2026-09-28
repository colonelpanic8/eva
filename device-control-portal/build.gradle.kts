plugins {
    kotlin("jvm")
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }

dependencies {
    api(project(":device-control-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":device-control-core")))
}

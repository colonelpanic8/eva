plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }

dependencies {
    api(project(":device-control-core"))
    api(project(":device-control-portal"))
    api(libs.kotlinx.serialization.json)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    api(libs.okhttp)
    implementation(libs.kaml)
    implementation(libs.jgit)
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":device-control-core")))
}

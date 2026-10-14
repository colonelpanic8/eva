plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
}
kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }
dependencies {
    api(libs.kotlinx.serialization.json)
    api(project(":diagnostics-core"))
    testImplementation(libs.junit)
}

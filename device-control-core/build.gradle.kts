plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}

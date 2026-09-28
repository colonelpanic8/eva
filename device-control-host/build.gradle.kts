plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    application
}
kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }
application {
    mainClass.set("com.colonelpanic.eva.devicecontrol.host.MainKt")
    applicationName = "eva-device"
}
dependencies {
    implementation(project(":device-control-core"))
    implementation(project(":device-control-portal"))
    implementation(libs.kotlinx.cli)
    implementation(libs.kaml)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":device-control-core")))
}
tasks.test {
    environment("EVA_HOST_TEST_SERIAL", System.getenv("EVA_HOST_TEST_SERIAL") ?: "")
}

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    application
}

kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }

application {
    mainClass.set("com.colonelpanic.eva.desktop.MainKt")
    applicationName = "eva-desktop"
}

// These JetBrains artifacts are class-free shims over the androidx ones and share their file names,
// which the application distribution cannot hold side by side.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-saveable-desktop")
}

dependencies {
    implementation(project(":eva-core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.nop)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
    implementation(libs.mcp.client)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":eva-core")))
}

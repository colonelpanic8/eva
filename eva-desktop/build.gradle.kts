plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    application
}

kotlin { jvmToolchain(17) }
ktlint { version.set("1.8.0") }

application {
    mainClass.set("com.colonelpanic.eva.desktop.MainKt")
    applicationName = "eva-desktop"
}

dependencies {
    implementation(project(":eva-core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.nop)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":eva-core")))
}

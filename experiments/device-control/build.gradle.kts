plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}
ktlint { version.set("1.8.0") }
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> { version.set("1.8.0") }
}

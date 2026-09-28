plugins {
    kotlin("jvm")
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

val onnxVersion = "1.30.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxVersion")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Model files are supplied locally and never bundled in the module.
tasks.register<JavaExec>("syntheticEvaluation") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.colonelpanic.eva.keyword.SyntheticEvaluation")
    args(providers.gradleProperty("keywordModelsDir").getOrElse(""))
}

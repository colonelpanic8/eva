import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

val releaseSigningEnvironment =
    mapOf(
        "ANDROID_KEYSTORE_FILE" to providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull,
        "ANDROID_KEYSTORE_PASSWORD" to providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull,
        "ANDROID_KEY_ALIAS" to providers.environmentVariable("ANDROID_KEY_ALIAS").orNull,
        "ANDROID_KEY_PASSWORD" to providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull,
    )
val configuredSigningValues = releaseSigningEnvironment.filterValues { !it.isNullOrBlank() }

require(configuredSigningValues.isEmpty() || configuredSigningValues.size == releaseSigningEnvironment.size) {
    "Release signing is partially configured; provide every ANDROID_KEYSTORE_* and ANDROID_KEY_* value."
}

android {
    namespace = "com.colonelpanic.eva.wear"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.colonelpanic.eva"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.environmentVariable("EVA_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("EVA_VERSION_NAME").orNull ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (configuredSigningValues.isNotEmpty()) {
        signingConfigs.create("release") {
            storeFile = file(checkNotNull(releaseSigningEnvironment["ANDROID_KEYSTORE_FILE"]))
            storePassword = releaseSigningEnvironment["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = releaseSigningEnvironment["ANDROID_KEY_ALIAS"]
            keyPassword = releaseSigningEnvironment["ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (configuredSigningValues.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
    }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
ktlint {
    version.set("1.8.0")
    android.set(true)
}
dependencies {
    implementation(project(":wear-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    // Play services brings Fragment 1.0; keep its lifecycle bridge compatible with Activity Result.
    implementation(libs.androidx.fragment)
    implementation(libs.wear.ongoing)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

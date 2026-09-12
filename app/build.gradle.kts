import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
    namespace = "com.colonelpanic.eva"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.colonelpanic.eva"
        minSdk = 23
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (configuredSigningValues.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
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

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ktlint {
    version.set("1.8.0")
    android.set(true)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

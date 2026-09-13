plugins { id("com.android.application") }
android {
    namespace = "com.colonelpanic.eva.deviceprobe"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.colonelpanic.eva.deviceprobe"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-experiment"
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { warningsAsErrors = true }
}
dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

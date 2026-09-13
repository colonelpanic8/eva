plugins { id("com.android.application") }
android {
    namespace = "com.colonelpanic.eva.devicefixture"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.colonelpanic.eva.devicefixture"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-experiment"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { warningsAsErrors = true }
}
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

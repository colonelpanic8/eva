pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EVA"
// The Nix desktop package builds without the Android SDK.
if (providers.gradleProperty("eva.desktopOnly").orNull != "true") {
    include(":app")
}

include(":eva-core")
include(":eva-desktop")
include(":device-control-core")
include(":device-control-portal")
include(":keyword-core")
include(":device-control-host")

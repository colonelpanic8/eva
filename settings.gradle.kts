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
include(":app")

include(":eva-core")
include(":eva-desktop")
include(":device-control-core")
include(":device-control-portal")
include(":keyword-core")
include(":device-control-host")

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

rootProject.name = "fart-looper"

include(
    ":app",
    ":design",
    ":core:media",
    ":core:network",
    ":core:simulator",
    ":feature:home",
    ":feature:library",
    ":feature:rules"
)

// Vendor submodules - Team A integration
include(":vendor:nanohttpd:core")
include(":vendor:cling:core")
include(":vendor:cling:support")
include(":vendor:mdns")

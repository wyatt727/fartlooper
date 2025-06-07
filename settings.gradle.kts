pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // LOCAL BUILD FINDING: FAIL_ON_PROJECT_REPOS conflicts with vendor submodule repositories
    // Vendor submodules reference deprecated BintrayJCenter, causing build failures
    // WARN mode allows vendor repos while still catching accidental project repo additions
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        // UPNP LIBRARY RESOLUTION: Modern UPnP/mDNS library sources
        // JitPack for UPnPCast - Modern Cling replacement
        maven {
            url = uri("https://jitpack.io")
        }

        // DM-UPnP is available on Maven Central, no additional repo needed
        // fr.distrimind.oss.upnp.android:DM-UPnP-Android:1.5.6

        // LEGACY: Original Cling dependencies (EOL, 4thline.org unreliable)
        // Keeping for fallback but preferring modern alternatives
        maven {
            url = uri("http://4thline.org/m2")
            isAllowInsecureProtocol = true
        }

        // VENDOR COMPATIBILITY FINDING: Some vendor dependencies still require jcenter
        // This can be removed once vendor submodules are updated or replaced with Maven deps
        @Suppress("DEPRECATION")
        jcenter()
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

// VENDOR BUILD FINDING: Exclude vendor submodules due to compatibility issues
// Vendor submodules use deprecated Gradle features (testCompile, BintrayJCenter)
// Use Maven Central dependencies instead for better compatibility
// include(":vendor:nanohttpd:core")
// include(":vendor:cling:core")
// include(":vendor:cling:support")
// include(":vendor:mdns")

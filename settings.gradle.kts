pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle plugin is published to Google's repository, not the
        // plugin portal.
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform pulls a few androidx artifacts that only Google
        // publishes. Filtering by group keeps the lookup local instead of
        // searching a second index for everything.
        google {
            content {
                includeGroupByRegex("androidx[.].*")
                includeGroupByRegex("com[.]android[.]tools.*")
            }
        }
    }
}

rootProject.name = "CubSync"

include(
    ":core",
    ":backends:local",
    ":backends:lan",
    ":service",
    ":app:desktop",
    ":app:android",
    ":cli",
    ":testing",
)

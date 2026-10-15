pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "CubSync"

include(
    ":core",
    ":backends:local",
    ":backends:lan",
    ":cli",
)

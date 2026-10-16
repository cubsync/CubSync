import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    // A window is not a terminal: the Swing dispatcher is what lets a coroutine
    // finish on the UI thread, which is where Compose state has to be written from.
    implementation(libs.kotlinx.coroutines.swing)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The screenshot test composes the real screens off screen, so it needs the
    // same runtime the window uses.
    testImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.cubsync.desktop.MainKt"

        nativeDistributions {
            // Msi and Exe are what a Windows user double-clicks; the app-image is
            // what `run` and the tests use.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CubSync"
            packageVersion = providers.gradleProperty("cubsync.version").get()
            description = "Cross-platform file synchronisation, one engine, many backends."
            vendor = "CubSync"
            copyright = "Apache-2.0"

            windows {
                // A stable id so an upgrade replaces the previous install instead of
                // sitting next to it.
                upgradeUuid = "6E1C2A44-1F4B-4A2E-9E7C-3C9E1D2B7A55"
                // The finalised mark, with a hand-tuned 16px layer: a two-circle
                // shape that is crisp at 512 still turns to mud in a taskbar.
                iconFile.set(project.file("src/main/resources/cubsync.ico"))
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

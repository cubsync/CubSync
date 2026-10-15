plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":backends:local"))
    implementation(project(":backends:lan"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    applicationName = "cubsync"
    mainClass.set("io.github.cubsync.cli.MainKt")
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The version lives in gradle.properties and nowhere else. Instead of a constant
// that a human has to keep in step, it is written into a resource file at build
// time, and BuildInfo reads that resource. A build that skips the task falls back
// to the jar's implementation version, and then to "0.0.0-dev" -- which is
// deliberately not a valid release number, so a mistake is visible.
val buildInfoDirectory = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfo by tasks.registering {
    val version = providers.gradleProperty("cubsync.version")
    val output = buildInfoDirectory.map { it.file("cubsync-build.properties") }
    inputs.property("cubsync.version", version)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=${version.get()}\n")
    }
}

sourceSets.main { resources.srcDir(buildInfoDirectory) }

tasks.named<ProcessResources>("processResources") { dependsOn(generateBuildInfo) }

dependencies {
    api(project(":core"))
    api(project(":backends:local"))
    api(project(":backends:lan"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing"))
}

tasks.named<Test>("test") { dependsOn(generateBuildInfo) }

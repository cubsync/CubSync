plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// A version code has to be a monotonic integer, and the version lives in one place.
// 0.4.1 becomes 401, 1.2.0 becomes 10200: monotonic as long as the version is, which
// is what a store requires.
val cubsyncVersion: String = providers.gradleProperty("cubsync.version").get()

private fun versionCodeOf(version: String): Int {
    val parts = version.split('-', '+', '.').mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "io.github.cubsync.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.cubsync"
        // `java.nio.file` and `java.time` are both API 26, and the local backend uses
        // them for every operation. Raising this would mean rewriting those paths.
        minSdk = 26
        targetSdk = 36
        versionName = cubsyncVersion
        versionCode = versionCodeOf(cubsyncVersion)
    }

    buildTypes {
        release {
            // The first releases are for people who can read a log. Shrinking can wait
            // until there is something worth obfuscating.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    lint {
        // Lint findings are worth reading, not worth failing a build over while the
        // project is pre-1.0.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Android's main dispatcher, so a coroutine can finish on the UI thread.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
}

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

    testOptions {
        unitTests {
            // Robolectric needs the module's resources, which is what makes the theme
            // and the launcher icon resolvable in a unit test.
            isIncludeAndroidResources = true
        }
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

    // The phone's screens are composed off screen and written to PNGs, which is how a
    // layout gets looked at without a device or an emulator. Robolectric drives a real
    // composition on the JVM; the test rule is what lets it be captured.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Robolectric fetches its `android-all` jar on first use, from Maven Central.
 *
 * On a machine that reaches Maven Central through a proxy, the proxy has to be passed
 * down to the forked test JVM; on CI there is no proxy set and this is a no-op rather
 * than a hard-coded one that would break there.
 *
 * The screenshot test runs on the debug variant only: it needs `ui-test-manifest` to
 * declare `ComponentActivity`, and that manifest is debug-only on purpose — shipping it
 * in a release APK would declare an activity the application does not have.
 */
tasks.withType<Test>().configureEach {
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    if (name.contains("Release")) {
        filter.excludeTestsMatching("*PhoneScreenshotTest*")
    }
}

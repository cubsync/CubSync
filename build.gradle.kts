import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Every Kotlin plugin is declared here with `apply false`, so the version is on
    // the classpath once. Applying one in a module without declaring it here makes
    // Gradle refuse the second one, because they share an implementation artifact.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Android's plugins have to be on the same classpath as the Kotlin Android
    // plugin: the latter reaches into the former's variant API while it is applied.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "io.github.cubsync"
    version = providers.gradleProperty("cubsync.version").get()
}

subprojects {
    // Every module compiles to the same bytecode level regardless of the JDK that
    // happens to run Gradle (21 locally, 17 on CI).
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Only the plain JVM modules. The Android Gradle plugin drives Java compilation
    // itself and refuses `--release`, because it compiles against the platform's
    // android.jar rather than the JDK's own API.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

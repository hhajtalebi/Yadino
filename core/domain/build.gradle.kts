// Pure Kotlin/JVM module: domain models and business rules (recurrence, habits,
// goal progress, reports, backup format & validation, settings model).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Target Java 17 bytecode with whatever JDK runs Gradle (17 or newer); no toolchain
// download is needed, so Android Studio's bundled JBR 21 works out of the box.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:calendar"))
    testImplementation(libs.junit)
}

// Pure Kotlin/JVM module: Solar Hijri (Jalali) calendar, formatting and occasions.
// It has no Android dependency so it can be unit tested on the JVM and reused.
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
    testImplementation(libs.junit)
}

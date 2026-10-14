// Pure Kotlin/JVM module: Solar Hijri (Jalali) calendar, formatting and occasions.
// It has no Android dependency so it can be unit tested on the JVM and reused.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

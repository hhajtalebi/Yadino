// Pure Kotlin/JVM module: domain models and business rules (recurrence, habits,
// goal progress, reports, backup format & validation, settings model).
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
    api(project(":core:calendar"))
    testImplementation(libs.junit)
}

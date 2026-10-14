import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------------------------
// Local, non-committed configuration (local.properties or environment variables):
//   yadino.drive.clientId      Google OAuth client id for the optional Drive backup
//   yadino.keystore            path of the release keystore
//   yadino.keystore.password   keystore password
//   yadino.key.alias           key alias (default: yadino)
//   yadino.key.password        key password (default: keystore password)
// Environment variable names are the upper-cased keys with '.' replaced by '_'
// (e.g. YADINO_DRIVE_CLIENTID, YADINO_KEYSTORE_PASSWORD).
// ---------------------------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    (localProps.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')))?.takeIf { it.isNotBlank() }

val driveClientId: String = prop("yadino.drive.clientId") ?: ""
val driveRedirectScheme: String =
    if (driveClientId.endsWith(".apps.googleusercontent.com")) {
        "com.googleusercontent.apps." + driveClientId.removeSuffix(".apps.googleusercontent.com")
    } else {
        "ir.yadino.app.oauth"
    }

android {
    namespace = "ir.yadino.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.yadino.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DRIVE_CLIENT_ID", "\"$driveClientId\"")
        buildConfigField("String", "DRIVE_REDIRECT_SCHEME", "\"$driveRedirectScheme\"")
        buildConfigField("String", "BUILD_TOOL", "\"gradle\"")
        manifestPlaceholders["driveRedirectScheme"] = driveRedirectScheme
    }

    signingConfigs {
        create("release") {
            val keystore = prop("yadino.keystore")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = prop("yadino.keystore.password")
                keyAlias = prop("yadino.key.alias") ?: "yadino"
                keyPassword = prop("yadino.key.password") ?: prop("yadino.keystore.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        // exported Room schemas are used by MigrationTestHelper in instrumented tests
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    bundle {
        // The UI language is applied at runtime; keep all language resources in every split.
        language { enableSplit = false }
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
        // Persian is the default (and only) language of the UI.
        disable += setOf("MissingTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)

    // JVM unit tests (run on the development machine)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)

    // Instrumented tests (run on a device or emulator)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

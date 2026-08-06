plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ provides built-in Kotlin support, so the standalone
    // org.jetbrains.kotlin.android plugin is no longer applied (applying it now
    // fails the build). The kotlin.compose and kotlin.serialization plugins are
    // still required — they're Kotlin *compiler* plugins, not the Android one.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "org.eidora"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.eidora"
        minSdk = 26
        // compileSdk is 37 (needed by newer libraries) but targetSdk stays at 35
        // on purpose: raising targetSdk opts into Android 17's runtime behaviour
        // changes (stricter background limits, HTTPS enforcement, etc.), which
        // should be adopted deliberately and tested — not as a side effect of a
        // library bump. Raising compileSdk alone changes nothing at runtime and
        // costs no users (minSdk is unaffected).
        targetSdk = 35
        // Versioning (Muster B): these values are the single source of truth.
        // F-Droid reads them directly from this file at the tag it builds — it
        // does NOT run our GitHub workflows. So to release:
        //   1. bump versionCode + versionName here, commit
        //   2. add fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt
        //   3. git tag v<versionName> on that commit and push the tag
        // versionCode must be a monotonically increasing integer. Convention:
        //   MAJOR*10000 + MINOR*100 + PATCH  (so 1.2.0 -> 10200). Keep each part
        //   below 100. versionName is the human string shown to users.
        versionCode = 10000
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed debug keystore checked into the repo so all builds
            // (local + GitHub Actions) share the same signature.
            // This lets debug APKs update each other without uninstall.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Keep TFLite model uncompressed
    androidResources {
        noCompress += "tflite"
        // Auto-generate res/xml/locales_config.xml from the values-* folders and
        // wire it into the manifest, so the per-app language picker (Android 13+,
        // backported below via AppCompat) always matches the shipped locales.
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Activity + ViewModel + Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Preferences DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")


    // TFLite for FaceNet
    implementation(libs.tensorflow.lite) {
        // Defensive: keep the proprietary ODML image helper (Android SDK
        // License) out of the graph so the build stays fully FOSS for F-Droid.
        exclude(group = "com.google.android.odml", module = "image")
    }
    implementation(libs.tensorflow.lite.gpu) {
        exclude(group = "com.google.android.odml", module = "image")
    }

    // ExifInterface for XMP
    implementation(libs.androidx.exifinterface)

    // Ashampoo XMP (Kotlin-native XMP Core port)
    implementation(libs.ashampoo.xmp)
    implementation(libs.snakeyaml)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Kotlin serialization (FaceRegionCoords JSON)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines

    // AppCompat (required for Theme.AppCompat)
    implementation(libs.androidx.appcompat)

    // Core KTX
    implementation(libs.androidx.core.ktx)

    // About / open-source license list
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    // Unit testing (JUnit 5)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Pin the launcher to match Jupiter — Gradle 8.x bundles an older 1.x
    // launcher, which fails test discovery against Jupiter 6.x.
    testRuntimeOnly(libs.junit.platform.launcher)
}

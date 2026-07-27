plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "org.eidora"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.eidora"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

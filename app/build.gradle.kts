plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.sebastian.faces"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.sebastian.faces"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Keep TFLite model uncompressed
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Activity + ViewModel + Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // ML Kit Face Detection
    implementation(libs.mlkit.face.detection)

    // TFLite for FaceNet
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.gpu)

    // ExifInterface for XMP
    implementation(libs.androidx.exifinterface)

    // Adobe XMP Core
    implementation(libs.xmpcore)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Kotlin serialization (FaceRegionCoords JSON)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines + Play Services (for ML Kit await())
    implementation(libs.kotlinx.coroutines.play.services)

    // Core KTX
    implementation(libs.androidx.core.ktx)
}

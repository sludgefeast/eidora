// Top-level build file – plugin declarations only.
// All dependency versions are managed in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin.android removed: AGP 9 provides built-in Kotlin support.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

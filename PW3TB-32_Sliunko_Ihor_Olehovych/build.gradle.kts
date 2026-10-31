// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // KSP — генерація коду Room (DAO та Database) під час компіляції
    alias(libs.plugins.ksp) apply false
}

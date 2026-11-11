// Top-level build file where you can add configuration options common to all sub-projects/modules.
// LiteRT-LM 0.18 publishes Kotlin 2.4 metadata; upgrade AGP's built-in compiler as well.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0") }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.google.services) apply false
}


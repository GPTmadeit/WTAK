// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    // AGP 8.11 is required to compile against SDK 36 (Android 16 / Wear OS 6).
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

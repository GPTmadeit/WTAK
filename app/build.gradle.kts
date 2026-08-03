import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials, in order of precedence:
//   1. Environment variables — for CI, where no secret should touch the disk.
//   2. keystore.properties — untracked local file for developer machines.
// With neither, release builds are simply left unsigned rather than failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    // Env wins, so CI can override a stale local file.
    System.getenv("ATAKWATCH_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("ATAKWATCH_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("ATAKWATCH_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("ATAKWATCH_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}

/** True only when every field needed to sign is present. */
val canSign = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProps.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.atakwatch.minimap"
    // Android 16 / Wear OS 6 — the platform the Pixel Watch 4 ships with.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atakwatch.minimap"
        // Wear OS 3 = API 30; target the current platform. This range covers
        // every supported watch from Wear OS 3 up to the Pixel Watch 4.
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "1.8.0"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")

                // v1 (JAR signing) is unnecessary at minSdk 30 and only slows
                // installs. v3 matters: it is the scheme that supports signing
                // key rotation, so a compromised key is recoverable instead of
                // permanent.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // BuildConfig.DEBUG gates the adb test hooks; VERSION_NAME feeds the
        // About screen so the displayed version can't drift from the build.
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle jars ship duplicate OSGI metadata that trips resource merging.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Compose (BOM keeps the core Compose artifacts in sync)
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    // Compose for Wear OS — Material 3 Expressive (Wear OS 6 / Pixel Watch 4 design system)
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("androidx.wear.compose:compose-foundation:1.5.0")
    implementation("androidx.wear.compose:compose-navigation:1.5.0")

    // Wear platform library — AmbientLifecycleObserver (always-on display)
    implementation("androidx.wear:wear:1.3.0")
    // Wearable Data Layer — pulls identity/config and CoT from a paired EUD.
    // play-services-base is declared explicitly: wearable pulls it in only as an
    // `implementation` dep, so inherited members (DataBuffer.close/iterator)
    // aren't otherwise visible at compile time.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Wear remote input — keyboard / handwriting / voice entry for the callsign
    implementation("androidx.wear:wear-input:1.1.0")

    // Tiles — glanceable status one swipe from the watch face
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    // ResolvableFuture, so the tile can return a ListenableFuture without
    // dragging all of Guava into a watch APK.
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // Lifecycle-aware Compose helpers + lifecycle-aware services
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // osmdroid — OpenStreetMap tiles, offline-capable, no API key (ATAK-style tiles)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // MGRS coordinate conversion — ATAK is MGRS-centric
    implementation("mil.nga:mgrs:2.1.3")

    // PKCS#10 CSR generation for TAK Server certificate enrollment
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests for the TAK Protocol codec
    testImplementation("junit:junit:4.13.2")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base { archivesName.set("Rafay Own Editor") }

val ksFile = rootProject.file("keystore.properties")

android {
    namespace = "com.rafay.autoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rafay.autoeditor"
        minSdk = 29          // Android 10+ (MediaStore RELATIVE_PATH, scaled frame grabs)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // keeps the APK small; all modern phones
    }

    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                val p = Properties().apply { ksFile.inputStream().use { load(it) } }
                storeFile = file(p["storeFile"] as String)
                storePassword = p["storePassword"] as String
                keyAlias = p["keyAlias"] as String
                keyPassword = p["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without keystore.properties the release APK is signed with the debug key (installable, personal use).
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Bundled (offline) on-device face detector: used to find real player footage.
    implementation("com.google.mlkit:face-detection:16.1.7")

    // FFmpeg for Android (community build of the retired ffmpeg-kit, same com.arthenica.ffmpegkit API).
    // If this coordinate ever stops resolving, see README "FFmpeg dependency".
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")

    testImplementation("junit:junit:4.13.2")
}

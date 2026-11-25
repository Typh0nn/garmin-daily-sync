import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.localagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.localagent"
        minSdk = 34
        targetSdk = 36
        // CI podaje numer buildu, żeby każda wersja instalowała się jako aktualizacja.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
        // Tylko Pixel (arm64): bez tego biblioteki MediaPipe dla 4 architektur dają APK ~150 MB.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Stały klucz podpisu (CI: sekret AGENT_DEBUG_KEYSTORE_B64 → plik → AGENT_KEYSTORE).
    // Bez niego build używa losowego klucza debug i nie nadaje się do aktualizacji na telefonie.
    signingConfigs {
        getByName("debug") {
            System.getenv("AGENT_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // Model YAMNet musi leżeć w assets bez kompresji (mapowanie pamięci przez MediaPipe).
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Klasyfikacja dźwięku otoczenia (YAMNet) na urządzeniu.
    implementation("com.google.mediapipe:tasks-audio:1.2.3")

    testImplementation("junit:junit:4.13.2")
}

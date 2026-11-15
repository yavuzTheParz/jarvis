import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bridgeToken = File(rootDir, "bridge/.token").takeIf { it.exists() }?.readText()?.trim() ?: ""

android {
    namespace = "app.jarvis"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.jarvis"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "BRIDGE_TOKEN", "\"$bridgeToken\"")
        // The S23, and the emulator it's tested on.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Runs the wake word models on the phone.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

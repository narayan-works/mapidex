import java.util.Properties

// GEMINI_API_KEY comes from local.properties (git-ignored) or the env — never commit it.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val geminiKey: String = (localProps.getProperty("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: "").trim()
// Rolling alias by default; pin e.g. GEMINI_MODEL=gemini-3.8-flash in local.properties if wanted.
val geminiModel: String = (localProps.getProperty("GEMINI_MODEL") ?: System.getenv("GEMINI_MODEL") ?: "gemini-flash-latest").trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.narayan.mapidex.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.narayan.mapidex.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.5.2"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // v0.5 Ask: on-device Gemini Nano (multimodal image+text) through Android AICore.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

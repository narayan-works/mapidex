plugins {
    // v0.5: bumped for ML Kit GenAI Prompt API (Gemini Nano) — its AAR is built with Kotlin 2.3,
    // which a 1.9 compiler can't read. Kotlin 2.x moves Compose to the compose compiler plugin.
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

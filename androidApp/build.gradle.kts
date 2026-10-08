plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
android {
    namespace = "org.intentional.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.intentional.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

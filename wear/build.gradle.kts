plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.elmdash.wear"
    compileSdk = 34
    // MÊME applicationId ET MÊME clé que le téléphone : obligatoire pour la liaison montre ⇄ téléphone
    defaultConfig {
        applicationId = "fr.elmdash"
        minSdk = 30; targetSdk = 34
        versionCode = 100 + runNumber
        versionName = "1.$runNumber"
    }
    signingConfigs {
        create("elmdash") {
            storeFile = rootProject.file("keystore/elmdash.p12")
            storePassword = "elmdash2026"; keyAlias = "elmdash"; keyPassword = "elmdash2026"
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("elmdash"); isMinifyEnabled = false }
        getByName("debug") { signingConfig = signingConfigs.getByName("elmdash") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.9.2")
}

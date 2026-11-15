plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "fr.elmdash.core"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2024.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.google.android.gms:play-services-wearable:18.2.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ponytail: one committed keystore for a personal sideloaded app — the signature must be stable
// across machines or Android refuses the in-place update and forces an uninstall, which wipes
// files/maps (user data). Password is nominal; the repo itself is the secret boundary.

android {
    namespace = "com.jrod.droidgridder"
    compileSdk = 36
    signingConfigs {
        create("droidmapper") {
            storeFile = file("droidmapper.keystore")
            storePassword = "droidmapper"
            keyAlias = "droidmapper"
            keyPassword = "droidmapper"
        }
    }
    defaultConfig {
        applicationId = "com.jrod.droidgridder"
        minSdk = 24; targetSdk = 36; versionCode = 8; versionName = "1.5.1"
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("droidmapper") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("droidmapper") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

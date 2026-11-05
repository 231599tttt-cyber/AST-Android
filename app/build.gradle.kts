plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ast.android"
    compileSdk = 34
    // Fixed demo signing key (demo.keystore, standard debug credentials, demo-only,
    // NOT a Play Store release key). Previous builds used a fresh temporary debug
    // key on each GitHub runner, so every APK had a different signature and
    // Android refused to update without uninstalling first. With this fixed key,
    // all future APKs share one signature and can be installed over the old
    // version without uninstalling, preserving the saved API key.
    // Note: the one transition from the old temporary-key builds to this fixed
    // key still requires one uninstall, after that updates are in-place.
    signingConfigs {
        create("demo") {
            storeFile = file("demo.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.ast.android"
        minSdk = 26
        targetSdk = 34
        // Bump on every release: Android update requires a higher versionCode.
        versionCode = 7
        versionName = "1.6"
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

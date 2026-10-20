plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.leymo.thorcraft.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.leymo.thorcraft.panel"
        minSdk = 30          // AYN Thor runs Android 13
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // sideload-friendly
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

// No extra libraries: plain Android framework only.

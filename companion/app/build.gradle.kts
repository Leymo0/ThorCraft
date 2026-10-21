plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build number from GitHub Actions (each release is "build-N"), so the app can tell when an update exists.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "dev.leymo.thorcraft.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.leymo.thorcraft.panel"
        minSdk = 30          // AYN Thor runs Android 13
        targetSdk = 34
        versionCode = 100 + buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("String", "REPO", "\"Leymo0/ThorCraft\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One fixed key for every build, so the app can update itself (Android only accepts an update
    // signed with the same key as the installed app). Hobby-project key, kept in the repo.
    signingConfigs {
        create("thorcraft") {
            storeFile = rootProject.file("thorcraft.keystore")
            storePassword = "thorcraft"
            keyAlias = "thorcraft"
            keyPassword = "thorcraft"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("thorcraft")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("thorcraft")
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
    // folder access for installing the mod into a launcher profile
    implementation("androidx.documentfile:documentfile:1.0.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vocab.anki"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vocab.anki"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed key kept in the repo so every build can install over the previous one.
        // Fine for a personal sideloaded app; don't reuse it for anything on the Play Store.
        create("sideload") {
            storeFile = file("vocab-release.keystore")
            storePassword = "vocabanki"
            keyAlias = "vocab"
            keyPassword = "vocabanki"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is stubbed out off-device).
    testImplementation("org.json:json:20240303")
}

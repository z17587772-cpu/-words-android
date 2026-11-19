plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.words.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.words.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.1"
    }

    signingConfigs {
        create("stable") {
            storeFile = file(System.getenv("WORDS_KEYSTORE_PATH") ?: "words-upload-key.jks")
            storePassword = System.getenv("WORDS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("WORDS_KEY_ALIAS") ?: "words"
            keyPassword = System.getenv("WORDS_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            // Keep one stable certificate across CI builds so Android can update in place.
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

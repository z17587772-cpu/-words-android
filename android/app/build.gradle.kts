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
        versionCode = 9
        versionName = "1.6.0"
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

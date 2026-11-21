plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val listeningCatalogUrl = providers.gradleProperty("LISTENING_CATALOG_URL").orNull
    ?: "https://raw.githubusercontent.com/z17587772-cpu/-words-android/content-inbox/catalog/catalog.json"
val listeningCatalogPublicKey = providers.gradleProperty("LISTENING_CATALOG_PUBLIC_KEY").orNull.orEmpty()
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

val stableKeystorePath = providers.environmentVariable("WORDS_SIGNING_KEYSTORE_PATH").orNull
val stableKeystorePassword = providers.environmentVariable("WORDS_SIGNING_STORE_PASSWORD").orNull
val stableKeyAlias = providers.environmentVariable("WORDS_SIGNING_KEY_ALIAS").orNull
val stableKeyPassword = providers.environmentVariable("WORDS_SIGNING_KEY_PASSWORD").orNull
val hasStableSigning = listOf(
    stableKeystorePath, stableKeystorePassword, stableKeyAlias, stableKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.words.app"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storePassword = stableKeystorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    defaultConfig {
        applicationId = "com.words.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 25
        versionName = "1.8.4"
        buildConfigField("String", "LISTENING_CATALOG_URL", buildConfigString(listeningCatalogUrl))
        buildConfigField("String", "LISTENING_CATALOG_PUBLIC_KEY", buildConfigString(listeningCatalogPublicKey))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.conscrypt:conscrypt-android:2.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

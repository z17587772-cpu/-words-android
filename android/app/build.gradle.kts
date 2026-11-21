plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val listeningCatalogUrl = providers.gradleProperty("LISTENING_CATALOG_URL").orNull.orEmpty()
val listeningCatalogPublicKey = providers.gradleProperty("LISTENING_CATALOG_PUBLIC_KEY").orNull.orEmpty()
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

android {
    namespace = "com.words.app"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.words.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 22
        versionName = "1.8.1"
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

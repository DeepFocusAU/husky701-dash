import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// what3words API key, kept out of the code: add w3w.apiKey=... to local.properties
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.husky701.dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.husky701.dash"
        minSdk = 28          // Note 10 shipped with Android 9, runs up to Android 12
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "W3W_API_KEY", "\"${localProps.getProperty("w3w.apiKey", "")}\"")
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so it installs straight away. Use your own key for keeps.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }     // android.util.Log in tests
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")       // the real org.json for What3Words tests
}

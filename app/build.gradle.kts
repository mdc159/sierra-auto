plugins {
    id("com.android.application")
}

// CI passes -PversionCode=<run number> so every build installs over the last one.
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing comes only from environment variables (set by CI from repo secrets).
// When they are absent (local and PR builds), release falls back to debug signing.
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val hasReleaseKey: Boolean = !releaseKeystorePath.isNullOrBlank() &&
    file(releaseKeystorePath).exists() &&
    !System.getenv("SIGNING_STORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("SIGNING_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("SIGNING_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "com.mdc159.sierra"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mdc159.sierra"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.activity:activity:1.13.0")

    testImplementation("junit:junit:4.13.2")
}

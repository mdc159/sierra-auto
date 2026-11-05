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
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
}

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
        // True only for play.yml builds (-Pplay=true): the app then leaves updates to Play.
        buildConfigField("boolean", "PLAY_BUILD", (findProperty("play") == "true").toString())
        // True only for debug builds, which install beside the Play app as a separate bench app.
        buildConfigField("boolean", "BENCH_BUILD", "false")
    }

    buildFeatures {
        buildConfig = true
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
        debug {
            // Bench app: its own package id, so a debug build installs beside the Play-installed
            // release instead of demanding an uninstall (different signer, lower versionCode).
            // The label comes from src/debug/res. The Kotlin namespace is unchanged.
            applicationIdSuffix = ".blitz"
            versionNameSuffix = "-blitz"
            buildConfigField("boolean", "BENCH_BUILD", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Play refuses UPDATE_PACKAGES_WITHOUT_USER_ACTION. play.yml builds with -Pplay=true, which
    // overlays src/play/AndroidManifest.xml on release to drop it from the Play bundle only.
    if (findProperty("play") == "true") {
        sourceSets.getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged manifest and resources to run car Screens on the JVM.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // CI runs `lintDebug` as a separate job: any error fails the build, reports are uploaded.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        xmlReport = true
        htmlReport = true
        sarifReport = true
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    // Android Auto implementation of CarAudioRecord (loaded reflectively by CarAudioRecord.create).
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // PhoneActivity keeps its conversation and transcript in a ViewModel across recreation.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0")
    // Updater: optional Shizuku path (the app runs without the Shizuku app installed).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
    // Car App Library test host (TestCarContext, FakeHost, ScreenController). It runs the real
    // template validators, including the PlaceListMapTemplate row rules, under Robolectric.
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16")
}

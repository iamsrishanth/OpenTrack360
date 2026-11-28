plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val releaseKeystore = System.getenv("OPENTRACK360_RELEASE_KEYSTORE")
val releaseStorePassword = System.getenv("OPENTRACK360_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPENTRACK360_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPENTRACK360_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }

if (releaseSigningRequested && !releaseSigningConfigured) {
    throw GradleException("Release signing requires the complete OPENTRACK360_RELEASE_* environment configuration.")
}

android {
    namespace = "me.srishanth.opentrack360"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.srishanth.opentrack360"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"https://track.srishanth.me/api/v1/\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.maplibre.android)
    implementation("com.google.zxing:core:3.5.4")

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.android)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.runner)
}

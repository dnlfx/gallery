plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from CI secrets (see README). Without them the release APK is unsigned.
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE")

android {
    namespace = "com.dnlfx.gallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dnlfx.gallery"
        // ContentResolver.loadThumbnail and scoped MediaStore columns need Android 10+.
        minSdk = 29
        targetSdk = 35
        // CI sets these from the build number so every release installs over the one before.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "gallery"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably faster, which matters most when flinging a big grid.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.media3.exoplayer)
    // On-device trimming and cropping for the editor.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself, so there's no separate Kotlin Android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from CI secrets (see README). Without them the release APK is unsigned.
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE")

android {
    namespace = "com.dnlfx.gallery"
    // Android 17. Compose and AndroidX now build against it.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dnlfx.gallery"
        // ContentResolver.loadThumbnail and scoped MediaStore columns need Android 10+.
        minSdk = 29
        // Android 17: predictive back, edge to edge and the newest platform behaviours.
        targetSdk = 37
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
    buildFeatures {
        compose = true
    }
    // Google Play's encrypted dependency report is of no use to a sideloaded app; leave it out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The Material 3 Expressive components and a few newer Compose APIs are still marked
        // experimental; opt in once here rather than file by file.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

composeCompiler {
    // Marks Uri and the app's own data as stable, so unchanged grid cells skip recomposition.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
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
    implementation(libs.androidx.compose.material.icons.core)
    // Installs the precompiled code profile (baseline-prof.txt plus the libraries' own) on first
    // launch, so a sideloaded APK starts and scrolls as fast as one from the Play Store.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.media3.exoplayer)
    // On-device trimming and cropping for the editor.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

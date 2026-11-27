plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jumpropeai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jumpropeai"
        minSdk = 24
        targetSdk = 35
        versionCode = 43
        versionName = "7.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Dual-Tier Offline Suite: bundle ARM 64-bit & 32-bit phone ABIs (100% offline on-device Neural VAR)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val keyAliasName = System.getenv("SIGNING_KEY_ALIAS")
    val keyAliasPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keyAliasName.isNullOrBlank() &&
        !keyAliasPassword.isNullOrBlank() &&
        file(keystorePath).exists()
    val ossKeystoreFile = file("jumpvex-oss.jks")

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyAliasPassword
            }
        }
        if (ossKeystoreFile.exists()) {
            create("ossRelease") {
                storeFile = ossKeystoreFile
                storePassword = "jumpvex-oss-signing-key"
                keyAlias = "jumpvex-oss"
                keyPassword = "jumpvex-oss-signing-key"
            }
        }
    }

    buildTypes {
        debug {
            // Distinct package ID so Debug & Release APKs can coexist without package conflict
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = when {
                hasReleaseKeystore -> signingConfigs.getByName("release")
                ossKeystoreFile.exists() -> signingConfigs.getByName("ossRelease")
                else -> signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Dual-Tier ML Kit Pose Backbones:
    // Tier 1 (Live 30 FPS): Fast Stream Pose | Tier 2 (Video Studio VAR): Accurate Heavy 3D Pose
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    // Media3 Transformer (hardware-accelerated video watermark export)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

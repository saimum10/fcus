plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Release signing — values come ONLY from environment variables (set by CI from
// repository secrets). Nothing secret is stored in the repository.
// If any value is missing, the release build type stays unsigned and CI skips
// release APKs entirely (see .github/workflows/build.yml).
// ---------------------------------------------------------------------------
val signingKeystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val signingKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

val hasReleaseSigning: Boolean =
    !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank() &&
        signingKeystoreFile?.let { file(it).isFile } == true

android {
    namespace = "com.focus.app"
    // Compose 1.13 (BOM 2026.11.01) requires compileSdk 37.1 or later. AGP 9.2 lists 37.0
    // as its newest supported level, so the warning for 37.1 is suppressed in gradle.properties.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.focus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // One real APK per ABI plus one universal APK. Each ABI APK contains only
    // the native libraries of its own ABI. (Do not combine with ndk.abiFilters.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
}

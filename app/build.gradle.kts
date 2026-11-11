plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- release signing -------------------------------------------------------
// The release APK is built unsigned (app-release-unsigned.apk) and signed outside
// the dev container with tools/sign-release.sh, so the signing key never enters
// the container. Debug builds use Android's debug key.

android {
    namespace = "rs.zivke.seedtag"
    compileSdk = 35

    defaultConfig {
        applicationId = "rs.zivke.seedtag"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            // No signingConfig on purpose: see the top of this file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }

    // No Google dependency-metadata blob in the APK (keeps builds reproducible / F-Droid friendly)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.bouncycastle)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
}

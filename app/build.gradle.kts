import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- release signing key ------------------------------------------------------
// KEYSTORE (JKS or PKCS12 path), KEY_PASS and optional KEY_ALIAS, from the
// environment or else from .env – same rules as luxtheme's build.sh: plain
// NAME=value lines, taken literally, one pair of quotes removed, first wins.
// The key is never generated here: an APK signed with another key won't install
// over the app. Debug builds use Android's debug key and don't need any of this.
fun signingValue(name: String): String? {
    System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { return it }
    val env = rootProject.file(".env")
    if (!env.isFile) return null
    val raw = env.readLines().map { it.trimEnd('\r') }
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=') ?: return null
    val quoted = raw.length >= 2 && raw.first() == raw.last() && (raw.first() == '"' || raw.first() == '\'')
    return (if (quoted) raw.substring(1, raw.length - 1) else raw).takeIf { it.isNotEmpty() }
}

val keystorePath = signingValue("KEYSTORE")
val keyPass = signingValue("KEY_PASS")
val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.isFile }
// Opening the store checks the password and tells JKS from PKCS12.
val keystore: KeyStore? = if (keystoreFile != null && keyPass != null) {
    runCatching { KeyStore.getInstance(keystoreFile, keyPass.toCharArray()) }.getOrNull()
} else null
val releaseKeyAlias: String? = signingValue("KEY_ALIAS") ?: keystore?.aliases()?.toList()?.singleOrNull()

val releaseSigningProblem: String? = when {
    keystorePath == null || keyPass == null -> "Set KEYSTORE and KEY_PASS in .env (see .devcontainer/README.md)."
    keystoreFile == null -> "Missing signing key: $keystorePath"
    keystore == null -> "Can't open $keystorePath: wrong KEY_PASS?"
    releaseKeyAlias == null -> "The keystore holds more than one key: set KEY_ALIAS in .env."
    else -> null
}

android {
    namespace = "rs.zivke.seedtag"
    compileSdk = 35

    defaultConfig {
        applicationId = "rs.zivke.seedtag"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigningProblem == null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keyPass
                storeType = keystore!!.type
                keyAlias = releaseKeyAlias
                keyPassword = keyPass // key password must equal the store password
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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

// Fail a release build up front (before R8) instead of producing an unsigned APK.
tasks.configureEach {
    if (name == "preReleaseBuild" && releaseSigningProblem != null) {
        val problem = releaseSigningProblem
        doFirst { throw GradleException(problem) }
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

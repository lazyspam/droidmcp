plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.droidmcp"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iputobject.droidmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.12.0"
    }

    // proot ships as lib*.so: it must be extracted to nativeLibraryDir, the only place apps may execute files from.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing comes from environment variables (set as secrets in CI):
    //   DROIDMCP_KEYSTORE (path), DROIDMCP_KEYSTORE_PASSWORD, DROIDMCP_KEY_ALIAS, DROIDMCP_KEY_PASSWORD
    // Without them, release builds fall back to the debug key so local `assembleRelease` still installs.
    val releaseKeystore = System.getenv("DROIDMCP_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("DROIDMCP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DROIDMCP_KEY_ALIAS")
                keyPassword = System.getenv("DROIDMCP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 drops unused code (most of the icon set, unused library code): much smaller APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

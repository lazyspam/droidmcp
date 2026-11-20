plugins {
    id("com.android.application")
}

android {
    namespace = "dev.droidmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.iputobject.droidmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
    }

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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

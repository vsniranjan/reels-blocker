import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives outside the repo: keystore.properties (gitignored)
// points at the keystore. Without the file, release builds are unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// One place to bump. defaultConfig and the APK filename both read from here, so
// a release cannot end up named after a version it does not contain.
val appVersionName = "2.1"
val appVersionCode = 5

android {
    namespace = "dev.niranjan.reelsblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.niranjan.reelsblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Ship the version in the filename. A folder of app-release.apk copies tells you
// nothing about which is which, and the name is what people download.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "reels-blocker-v$appVersionName-${variant.buildType}.apk"
            )
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

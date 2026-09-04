plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.goreg39.localbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.goreg39.localbridge"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0-dev"
    }

    signingConfigs {
        create("localBridgeDev") {
            val devKeyAlias = "localbridge-dev"
            storeFile = rootProject.file("signing/local-bridge-dev.keystore")
            storePassword = devKeyAlias
            keyAlias = devKeyAlias
            keyPassword = devKeyAlias
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("localBridgeDev")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

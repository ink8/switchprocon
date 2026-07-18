plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PbuildNumber=<GitHub run number>; local builds default to 1.
// versionCode drives the in-app auto-updater's "is there something newer?" check.
val buildNumber = (project.findProperty("buildNumber") as String? ?: "1").toInt()

android {
    namespace = "com.ink8.switchprocon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ink8.switchprocon"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
    }

    // One permanent key for every build (local + CI) so any APK updates over any other.
    signingConfigs {
        create("shared") {
            storeFile = file("switchprocon.keystore")
            storePassword = "switchprocon"
            keyAlias = "switchprocon"
            keyPassword = "switchprocon"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
        viewBinding = true
        buildConfig = true // updater compares BuildConfig.VERSION_CODE against GitHub
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
}

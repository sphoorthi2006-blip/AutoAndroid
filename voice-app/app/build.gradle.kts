plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kokorospeak"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.kokorospeak"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Most modern phones are arm64. If you test on an x86_64 emulator,
        // add "x86_64" here AND copy the x86_64 native libs into jniLibs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Don't try to compress the big model files (faster builds).
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

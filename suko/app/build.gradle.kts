plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.suko.app"
    compileSdk = 34
    (project.findProperty("ndkVer") as String?)?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "com.suko.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-step2"
        // arm64 only: the picture analyser runs the desktop Python code through Chaquopy (numpy + OpenCV wheels), which is shipped for 64-bit phones here
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++14" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Step 30: a release APK is signed with the owner's key when these environment variables exist (GitHub secrets); without them it falls back to the debug key so it still builds.
    val ks = System.getenv("BEZALEL_KEYSTORE")
    if (ks != null && File(ks).isFile) {
        signingConfigs.create("bezalel") {
            storeFile = File(ks)
            storePassword = System.getenv("BEZALEL_STORE_PASSWORD")
            keyAlias = System.getenv("BEZALEL_KEY_ALIAS")
            keyPassword = System.getenv("BEZALEL_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("bezalel") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

chaquopy {
    defaultConfig {
        // Chaquopy's wheel repo has OpenCV only for Python 3.8 and 3.10 (opencv-python-headless 4.5.1.48), so the app runs Python 3.10.
        // Versions are pinned: unpinned, pip picks the newest PyPI source package, which Chaquopy cannot compile.
        version = "3.10"
        buildPython("python3")
        pip {
            install("numpy==1.26.2")
            install("opencv-python-headless==4.5.1.48")
            install("pillow==9.2.0")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.auto.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.auto.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
}

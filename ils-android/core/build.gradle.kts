import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// PC-only check of the element stage: ./gradlew :core:elementsTest
tasks.register<JavaExec>("elementsTest") {
    group = "verification"
    description = "Runs ils.core.ElementsTest on a synthetic image."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ils.core.ElementsTest")
}

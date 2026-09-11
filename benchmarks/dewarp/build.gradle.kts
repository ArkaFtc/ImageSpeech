plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.0"
}
android {
    namespace = "com.example.dewarpbenchmark"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.dewarpbenchmark"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].java.srcDir("../ppocr/src/main/java/runtime")
    sourceSets["main"].assets.srcDir("../../app/src/main/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}

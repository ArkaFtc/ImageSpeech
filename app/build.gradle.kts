plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.test_project"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.test_project"
        // Side-by-side device validation when the installed app has a different signing key.
        val readerBenchmark = providers.gradleProperty("readerBenchmark").orNull == "true"
        if (readerBenchmark) applicationId = "com.example.test_project.reading"
        manifestPlaceholders["appLabel"] = if (readerBenchmark) "ImageSpeech Reading Test" else "@string/app_name"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // Lets JVM tests touch android.util.Log and SystemClock without a device.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // The camera screen and the review screen are two fragments over one Activity: the OCR engine,
    // the voice and the model binding are expensive enough that they have to outlive the move
    // between screens, and the captured frame has to survive it without going through an Intent.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    // PP-OCRv6 is the only OCR stack: specialised, more accurate than a general vision model at
    // this one job, and cheap enough to run on every press. ML Kit text recognition was removed
    // rather than kept alongside it.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.opencv:opencv:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device Gemma 4 E2B. The ~2.6 GB model file is downloaded once after install, not bundled.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // The model download survives process death, resumes, and waits for Wi-Fi.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

}

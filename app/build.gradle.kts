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
        buildConfigField("boolean", "READER_BENCHMARK", readerBenchmark.toString())
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

}

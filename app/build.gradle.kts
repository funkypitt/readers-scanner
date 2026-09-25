plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.freedomfighter.readersscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freedomfighter.readersscanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
        // Phones, and x86_64 for the emulator; -Pabis=arm64-v8a for a phone-only APK.
        ndk { abiFilters += ((project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
    }

    buildTypes { release { isMinifyEnabled = false } }

    // publique: free software only (F-Droid). prive: the same app plus Google's ML Kit (document
    // scanner and text recognition) to compare with; its own package, so both can be installed.
    flavorDimensions += "audience"
    productFlavors {
        create("publique") { dimension = "audience" }
        create("prive") {
            dimension = "audience"
            applicationIdSuffix = ".prive"
            versionNameSuffix = "-prive"
            resValue("string", "app_name", "Reader\\'s Scanner (privé)")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // The language models are read once from the APK and copied out; no need to compress them.
    androidResources { noCompress += listOf("traineddata", "ttf") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Tesseract 5 + Leptonica, on the phone (Apache 2.0).
    // Single-threaded build: the OpenMP one aborted in __kmp_fatal on arm64 (seen under the emulator's
    // arm translation, 2026-09-24), and Tesseract's own maintainers advise against OpenMP.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")
    // Private build only: Google ML Kit, on the phone (needs Google Play services for the scanner).
    "priveImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    "priveImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "priveImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// The private build carries ML Kit's native OCR (11 MB): its libraries go in compressed, so the
// APK stays under the 50 MB a Telegram bot accepts. Android unpacks them at install.
androidComponents {
    onVariants(selector().withFlavor("audience" to "prive")) { v ->
        v.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

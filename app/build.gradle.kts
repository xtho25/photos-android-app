plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voicepick.photos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicepick.photos"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // Native-library ABIs are set per edition below (real phones are ARM; no x86/emulator builds).
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Two editions from one codebase. Same applicationId, so installing one replaces the other
    // (settings, working folder and search index are kept).
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            resValue("string", "app_name", "Photo Chooser")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("ai") {
            dimension = "edition"
            versionNameSuffix = "-ai"
            resValue("string", "app_name", "Photo Chooser AI")
            // 64-bit only: phones fast enough for on-device AI indexing are all arm64 (saves ~40 MB).
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    sourceSets {
        // TinyCLIP ONNX files are produced by tools/convert_tinyclip.py into the git-ignored
        // models/ folder (too big for git) and bundled into the AI edition only.
        getByName("ai").assets.srcDir(rootProject.file("models/assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    // ONNX models are memory-mapped straight from the APK, which needs them stored uncompressed.
    androidResources {
        noCompress += "onnx"
    }
    // Compress native libraries in the APK (smaller download); Android extracts them at install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // On-device text recognition (Latin script), model bundled in the APK.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Background indexing.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // AI edition only: TinyCLIP runtime and on-device Greek→English query translation.
    "aiImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    "aiImplementation"("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    // Lets JVM tests run the real TinyCLIP text encoder (skipped when models/ is missing).
    "testAiImplementation"("com.microsoft.onnxruntime:onnxruntime:1.31.1")
}

// Fail early with a clear message when the AI edition is built without the converted model.
val requiredModelFiles = listOf("clip/image_encoder.onnx", "clip/text_encoder.onnx")
tasks.configureEach {
    if (name.startsWith("merge") && name.contains("Ai") && name.endsWith("Assets")) {
        doFirst {
            val missing = requiredModelFiles.filterNot { rootProject.file("models/assets/$it").isFile }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "AI edition needs the TinyCLIP model (missing: $missing). Run:\n" +
                        "  ~/.cache/photo-chooser-tools/venv/bin/python -I tools/convert_tinyclip.py",
                )
            }
        }
    }
}

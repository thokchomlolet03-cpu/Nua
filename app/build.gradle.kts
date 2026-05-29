import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.nua.production.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.nua.production.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "4.0"

        externalNativeBuild {
            cmake {
                // Whisper relies on NEON/FP16 CPU acceleration natively on Android
            }
        }

        ndk {
            // Hardened System Fix: Mandate 64-bit compilation environments exclusively
            // Drops armeabi-v7a (32-bit ARM) and x86 (32-bit Emulator) to eliminate runtime crashes
            abiFilters.clear()
            val enableSplits = project.hasProperty("splitApks") || !project.hasProperty("debugBuild")
            if (!enableSplits) {
                abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
            }
        }
    }

    assetPacks += mutableSetOf(":models", ":ai_models_asset_pack", ":premium_models_asset_pack")

    signingConfigs {
        create("release") {
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt")
        }
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    splits {
        // Configure explicit APK split options to safeguard distribution sizes
        abi {
            isEnable = project.hasProperty("splitApks") || !project.hasProperty("debugBuild")
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "Instantiatable"
    }
}


kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Lifecycle & Work
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation("androidx.work:work-runtime-ktx:2.9.0")

  // Compose UI
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Navigation3
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // AI & ML — LiteRT-LM (on-device NPU-accelerated inference)
  implementation(libs.litertlm.android)

  // Firebase AI Logic (cloud Gemini 3.5 Flash access)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.ai)

  // Media3 ExoPlayer (dual-player sync engine)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)

  // Zero-Copy Serialization (FlatBuffers)
  implementation(libs.flatbuffers.java)

  // ASR — Whisper (offline speech recognition)
  // compiled natively via CMake

  // Network (model downloads, video URL fetching)
  implementation(libs.okhttp)

  // Play Asset Delivery (PAD)
  implementation("com.google.android.play:asset-delivery-ktx:2.2.2")

  // JSON Serialization (kept for legacy manifest migration)
  implementation("io.coil-kt:coil-compose:2.5.0")
  implementation(libs.kotlinx.serialization.json)

  // Testing
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.kotlin)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("verifyApk64BitExclusivity") {
    group = "verification"
    description = "Statically audits compiled APK outputs to ensure zero legacy 32-bit native library leaks exist."
    
    dependsOn("assembleDebug")
    
    val apkFileProvider = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    
    doLast {
        val debugApk = apkFileProvider.get().asFile
        
        if (!debugApk.exists()) {
            throw GradleException("Verification Failure: Compiled app-debug.apk not found. Run ./gradlew assembleDebug first.")
        }
        
        var legacy32BitLeakFound = false
        val invalidPaths = mutableListOf<String>()
        
        // Open the compiled binary artifact structure using Java's zip file provider
        ZipFile(debugApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = entry.name
                
                // Assert that no 32-bit directory branches exist inside the compilation tree
                if (path.contains("lib/armeabi-v7a/") || path.contains("lib/x86/")) {
                    legacy32BitLeakFound = true
                    invalidPaths.add(path)
                }
            }
        }
        
        if (legacy32BitLeakFound) {
            System.err.println("CRITICAL BUILD SECURITY VULNERABILITY DETECTED")
            invalidPaths.forEach { System.err.println(" leaked legacy 32-bit artifact: $it") }
            throw GradleException("Verification Failure: Legacy 32-bit binaries detected in distribution package. Exclusive 64-bit target rules violated.")
        } else {
            println("SUCCESS: Static binary audit completed cleanly. Zero 32-bit compilation vectors found inside the package file structure.")
        }
    }
}


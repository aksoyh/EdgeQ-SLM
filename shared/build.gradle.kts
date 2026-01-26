plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android()
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.onnxruntime.android)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.workmanager.ktx)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.aksoyapps.edgeqslm.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        // NDK configuration for llama.cpp
        ndk {
            // Only build for arm64-v8a (modern 64-bit ARM devices)
            abiFilters += listOf("arm64-v8a")
        }

        // External native build configuration
        externalNativeBuild {
            cmake {
                // CMake arguments for llama.cpp build
                // LLAMA_CPP_PATH can be set in local.properties or as environment variable
                val llamaCppPath = project.findProperty("LLAMA_CPP_PATH")?.toString()
                    ?: System.getenv("LLAMA_CPP_PATH")
                    ?: "${System.getProperty("user.home")}/llama.cpp"
                
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_CPP_PATH=$llamaCppPath"
                )
                // Use safe math flags compatible with llama.cpp
                cppFlags += listOf("-O3", "-fno-finite-math-only")
            }
        }
    }

    // CMake build configuration
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // NDK version - use a stable version that supports llama.cpp
    ndkVersion = "26.1.10909125"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Build types configuration
    buildTypes {
        release {
            isMinifyEnabled = false
            // Native libraries will be stripped in CMake
        }
        debug {
            isJniDebuggable = true
        }
    }

    // Packaging options for native libraries
    packaging {
        jniLibs {
            // Keep all native libraries
            keepDebugSymbols += listOf("**/*.so")
            // Use legacy packaging for better compatibility
            useLegacyPackaging = true
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

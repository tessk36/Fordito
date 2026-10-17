plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redravencomputing.whispercore"
    compileSdk = 36

    buildFeatures { buildConfig = true }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-O3"
                cFlags += "-O3"
                arguments.addAll(
                    listOf(
                        "-DGGML_USE_CPU=ON",
                        "-DWHISPER_SUPPORT_COREML=OFF",
                        "-DWHISPER_SUPPORT_METAL=OFF",
                        "-DWHISPER_NATIVE=OFF",
                        "-DWHISPER_ACCELERATE=OFF",
                        "-DWHISPER_OPENBLAS=OFF",
                        "-DGGML_BLAS_OFF=ON",
                        "-DGGML_CUDA_OFF=ON",
                        "-DGGML_METAL_OFF=ON",
                        "-DGGML_OPENCL_OFF=ON",
                        "-DGGML_VULKAN_OFF=ON",
                        // A whisper.cpp a Git-et keresné a verzióbélyeghez — kikapcsoljuk,
                        // hogy Git nélkül is forduljon.
                        "-DGGML_BUILD_NUMBER=0",
                        "-DGGML_BUILD_COMMIT=unknown",
                        "-DGIT_EXE=echo",
                        // KRITIKUS a sebességhez: debug buildben is teljes optimalizálás,
                        // különben a Whisper 10-50x lassabb (-O0).
                        "-DCMAKE_C_FLAGS_DEBUG=-O3",
                        "-DCMAKE_CXX_FLAGS_DEBUG=-O3"
                    )
                )
                // Csak a Poco F7 Pro architektúrája — gyorsabb build.
                abiFilters.addAll(listOf("arm64-v8a"))
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

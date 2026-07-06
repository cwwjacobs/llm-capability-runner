plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.terminus.edge.gguf"
  compileSdk = 35
  ndkVersion = "29.0.13113456"

  defaultConfig {
    minSdk = 31
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    externalNativeBuild {
      cmake {
        arguments += listOf(
          "-DCMAKE_BUILD_TYPE=Release",
          "-DBUILD_SHARED_LIBS=ON",
          "-DLLAMA_BUILD_APP=OFF",
          "-DLLAMA_BUILD_COMMON=ON",
          "-DLLAMA_BUILD_MTMD=ON",
          "-DLLAMA_OPENSSL=OFF",
          "-DGGML_NATIVE=OFF",
          "-DGGML_BACKEND_DL=OFF",
          "-DGGML_CPU_ALL_VARIANTS=OFF",
          "-DGGML_OPENMP=OFF",
          "-DGGML_LLAMAFILE=OFF",
          "-DMTMD_VIDEO=OFF",
        )
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.31.6"
      // Keep FetchContent below Windows' legacy MAX_PATH while the checkout
      // remains pinned and reproducible from the project CMake file.
      buildStagingDirectory = file("C:/temp/edge-lite-cxx/ggufruntime")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
}

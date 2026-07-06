plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.terminus.edge.light"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.terminus.edge.light"
    minSdk = 31
    targetSdk = 35
    versionCode = 6
    versionName = "0.6.0"
    buildConfigField(
      "String",
      "LITERT_LM_VERSION",
      "\"${libs.versions.litertlm.get()}\"",
    )
    buildConfigField(
      "String",
      "GGUF_RUNTIME_VERSION",
      "\"cb295bf59663cd3577389315636772f4060bd1f5\"",
    )

    ndk { abiFilters += "arm64-v8a" }
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    manifestPlaceholders["appAuthRedirectScheme"] = "edge-lite"
  }

  buildTypes {
    debug {
      // Keep x86_64 only for the local Android emulator.
      ndk { abiFilters += "x86_64" }
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  kotlinOptions { jvmTarget = "21" }
  
  kotlin {
    jvmToolchain(21)
  }
  
  java {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(21))
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.material.icon.extended)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.litertlm)
  implementation(project(":ggufruntime"))
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.json)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.openid.appauth)
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

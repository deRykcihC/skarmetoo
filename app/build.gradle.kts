import java.io.InputStreamReader
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.spotless)
}

val releaseTestBuild = providers.gradleProperty("releaseTestBuild").orNull == "true"

// Keep local builds working before a Firebase project is connected. Once the Firebase console's
// google-services.json is placed in app/, the standard resource generation is enabled.
// The separate performance-test package has no Firebase registration.
if (file("google-services.json").exists() && !releaseTestBuild) {
  apply(plugin = "com.google.gms.google-services")
}

val buildingBundle =
    gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

android {
  namespace = "com.deryk.skarmetoo"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.deryk.skarmetoo"
    minSdk = 29
    targetSdk = 36
    versionCode = 43
    versionName = "1.21"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    if (buildingBundle) {
      ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
  }

  val keystorePropertiesFile = rootProject.file("key.properties")
  val keystoreProperties = Properties()
  if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(InputStreamReader(keystorePropertiesFile.inputStream(), "UTF-8"))
  }

  signingConfigs {
    create("release") {
      storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
      storePassword = keystoreProperties["storePassword"] as? String
      keyAlias = keystoreProperties["keyAlias"] as? String
      keyPassword = keystoreProperties["keyPassword"] as? String
    }
  }

  buildTypes {
    debug { applicationIdSuffix = ".alt" }

    release {
      if (releaseTestBuild) {
        applicationIdSuffix = ".test"
      }
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("release")
      ndk { debugSymbolLevel = "FULL" }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures { compose = true }

  splits {
    abi {
      isEnable = !buildingBundle
      reset()
      include("arm64-v8a", "x86_64")
      isUniversalApk = false
    }
  }

  bundle { language { enableSplit = false } }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.foundation.layout)
  implementation(libs.androidx.compose.material3)
  implementation(libs.tflite)
  implementation(libs.litertlm)
  implementation("androidx.work:work-runtime-ktx:2.11.1")
  implementation(libs.androidx.compose.navigation)
  implementation(libs.material.icon.extended)
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("io.coil-kt:coil-compose:2.5.0")
  implementation(libs.androidx.palette)
  implementation(libs.localagents.rag)
  implementation(libs.llamatik.library)
  implementation(libs.mlkit.genai)
  implementation(libs.protobuf.javalite)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.messaging)
  implementation("com.google.android.play:feature-delivery:2.1.0")
  testImplementation(libs.junit)
  // JVM tests use Android's JSON API to validate LAN protocol messages.
  testImplementation("org.json:json:20250107")
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

spotless {
  kotlin {
    target("**/*.kt")
    ktfmt()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktfmt()
  }
}

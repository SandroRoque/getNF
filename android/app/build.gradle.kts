import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

// Exported schemas are committed: each app version must migrate the previous one (D45).
room { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "io.github.sandroroque.getnf"
    // Grant runtime permissions (CAMERA) on install, so instrumented camera tests don't depend on a manual
    // `pm grant` that each connectedAndroidTest uninstall wipes.
    installation { installOptions += listOf("-g") }
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.sandroroque.getnf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Server defaults baked into the APK so the app works without typing anything.
    // Debug builds point at the dev PocketBase (host machine as seen from the emulator) and use
    // the app login from server/.env.local (gitignored). Release builds start empty: set them in Settings.
    val devEnv = Properties().apply {
      providers.fileContents(rootProject.layout.projectDirectory.file("../server/.env.local")).asText.orNull
        ?.let { load(it.reader()) }
    }
    fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_URL", quoted("http://10.0.2.2:8090"))
            buildConfigField("String", "DEFAULT_EMAIL", quoted(devEnv.getProperty("DEV_APP_EMAIL")))
            buildConfigField("String", "DEFAULT_PASSWORD", quoted(devEnv.getProperty("DEV_APP_PASSWORD")))
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_URL", quoted(""))
            buildConfigField("String", "DEFAULT_EMAIL", quoted(""))
            buildConfigField("String", "DEFAULT_PASSWORD", quoted(""))
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  // Room on the JVM for local tests: a bundled SQLite instead of the device's framework one.
  testImplementation(libs.androidx.sqlite.bundled.jvm)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Camera + QR decoding (ML Kit bundled model: no Play Services needed)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.camera.mlkit.vision)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.opencv)
  implementation(libs.androidx.exifinterface)

  // Storage + upload
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.room.runtime)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.okhttp)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

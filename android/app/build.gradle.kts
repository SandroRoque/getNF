import java.util.Base64
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

/** Why the release build can't carry this server, or null when it can (D105). */
fun releaseServerProblem(url: String?, key: String?): String? = when {
  url.isNullOrBlank() || key.isNullOrBlank() ->
    "release build: no Supabase URL and publishable key. Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in " +
      "server/.env.local, or pass -Pgetnf.releaseSupabaseUrl and -Pgetnf.releaseSupabaseKey"
  !url.startsWith("https://") -> "release build: the Supabase URL must be HTTPS (release builds refuse plain HTTP)"
  !isPublishableKey(key) -> "release build: the Supabase key isn't a publishable key. A secret key must never go in an APK"
  else -> null
}

/** A new-style publishable key, or a legacy JWT whose role is anon. */
fun isPublishableKey(key: String): Boolean {
  if (key.startsWith("sb_publishable_")) return true
  val parts = key.split('.')
  if (parts.size != 3) return false
  val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull() ?: return false
  return Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(payload)
}

var releaseServerProblem: String? = null

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

    // The Supabase project baked into the APK (D70): its URL and publishable key, which are public by design (Row
    // Level Security protects the data). Debug builds read them from server/.env.local (gitignored), and
    // -Pgetnf.supabaseUrl / -Pgetnf.supabaseKey override that, e.g. to point the emulator at a local Supabase
    // (http://10.0.2.2:54321). Release builds carry the online project (D105): from server/.env.local or
    // -Pgetnf.releaseSupabaseUrl / -Pgetnf.releaseSupabaseKey, never the debug override, HTTPS and a publishable key
    // only, or the release build stops. Nobody is signed in by default.
    // A git worktree (.claude/worktrees/<name>/) has no copy of the gitignored file: use the main checkout's.
    val devEnv = Properties().apply {
      listOf("../server/.env.local", "../../../../server/.env.local").firstNotNullOfOrNull {
        providers.fileContents(rootProject.layout.projectDirectory.file(it)).asText.orNull
      }?.let { load(it.reader()) }
    }
    fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val supabaseUrl = providers.gradleProperty("getnf.supabaseUrl").orNull ?: devEnv.getProperty("SUPABASE_URL")
    val supabaseKey = providers.gradleProperty("getnf.supabaseKey").orNull ?: devEnv.getProperty("SUPABASE_PUBLISHABLE_KEY")
    val releaseUrl = providers.gradleProperty("getnf.releaseSupabaseUrl").orNull ?: devEnv.getProperty("SUPABASE_URL")
    val releaseKey = providers.gradleProperty("getnf.releaseSupabaseKey").orNull ?: devEnv.getProperty("SUPABASE_PUBLISHABLE_KEY")
    releaseServerProblem = releaseServerProblem(releaseUrl, releaseKey)

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SUPABASE_URL", quoted(supabaseUrl))
            buildConfigField("String", "DEFAULT_SUPABASE_KEY", quoted(supabaseKey))
        }
        release {
            val ok = releaseServerProblem == null
            buildConfigField("String", "DEFAULT_SUPABASE_URL", quoted(if (ok) releaseUrl else ""))
            buildConfigField("String", "DEFAULT_SUPABASE_KEY", quoted(if (ok) releaseKey else ""))
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

    // The parser fixtures live with the server's tests; the Kotlin port reads the same files (D48).
    sourceSets["test"].resources.srcDir("../../server/test/fixtures")
    // The parity helper and goldens are shared with the on-device tests (Android's regex engine differs).
    sourceSets["test"].kotlin.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].kotlin.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].resources.srcDirs("../../server/test/fixtures", "src/test/resources")

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
  androidTestImplementation(libs.androidx.room.testing)

  // Camera + QR decoding (ML Kit bundled model: no Play Services needed)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.camera.mlkit.vision)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.mlkit.text.recognition)
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

// Stop a release build that would ship without a server, or with the wrong one (D105). Debug builds don't care.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
  // A plain String, so the configuration cache can store the action.
  val problem: String = releaseServerProblem ?: return@configureEach
  doFirst { throw GradleException(problem) }
}

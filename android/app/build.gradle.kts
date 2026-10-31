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
      ".env.local, or pass -Pgetnf.releaseSupabaseUrl and -Pgetnf.releaseSupabaseKey"
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
var uploadKeyMissing: String? = null

// Exported schemas are committed: each app version must migrate the previous one (D45).
room { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "io.github.sandroroque.getnf"
    // Grant runtime permissions (CAMERA) on install, so instrumented camera tests don't depend on a manual
    // `pm grant` that each connectedAndroidTest uninstall wipes.
    installation { installOptions += listOf("-g") }
    compileSdk = 36
    defaultConfig {
        // The Play listing's id, permanent once the app is created there (D113). The Kotlin package (namespace) keeps
        // its old name: it's never seen and nothing depends on it.
        applicationId = "br.com.roqlab.caderneta"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.3"
    }

    // The Supabase project baked into the APK (D70): its URL and publishable key, which are public by design (Row
    // Level Security protects the data). Debug builds read them from .env.local (gitignored), and
    // -Pgetnf.supabaseUrl / -Pgetnf.supabaseKey override that, e.g. to point the emulator at a local Supabase
    // (http://10.0.2.2:54321). Release builds carry the online project (D105): from .env.local or
    // -Pgetnf.releaseSupabaseUrl / -Pgetnf.releaseSupabaseKey, never the debug override, HTTPS and a publishable key
    // only, or the release build stops. Nobody is signed in by default.
    // A git worktree (.claude/worktrees/<name>/) has no copy of the gitignored file: use the main checkout's.
    val devEnv = Properties().apply {
      listOf("../.env.local", "../../../../.env.local").firstNotNullOfOrNull {
        providers.fileContents(rootProject.layout.projectDirectory.file(it)).asText.orNull
      }?.let { load(it.reader()) }
    }
    fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val supabaseUrl = providers.gradleProperty("getnf.supabaseUrl").orNull ?: devEnv.getProperty("SUPABASE_URL")
    val supabaseKey = providers.gradleProperty("getnf.supabaseKey").orNull ?: devEnv.getProperty("SUPABASE_PUBLISHABLE_KEY")
    val releaseUrl = providers.gradleProperty("getnf.releaseSupabaseUrl").orNull ?: devEnv.getProperty("SUPABASE_URL")
    val releaseKey = providers.gradleProperty("getnf.releaseSupabaseKey").orNull ?: devEnv.getProperty("SUPABASE_PUBLISHABLE_KEY")
    releaseServerProblem = releaseServerProblem(releaseUrl, releaseKey)

    // Release signing (D105): the Play upload key lives outside the repo, made by tools/make-upload-key.sh. Its
    // properties file (storeFile, storePassword, keyAlias, keyPassword) is ~/.config/getnf/upload-key.properties,
    // or -Pgetnf.signingProperties=<path>. Without it, release builds come out unsigned and bundleRelease stops.
    val signingPath = providers.gradleProperty("getnf.signingProperties").orNull
      ?: "${System.getProperty("user.home")}/.config/getnf/upload-key.properties"
    val signing = Properties().apply {
      providers.fileContents(rootProject.layout.projectDirectory.file(signingPath)).asText.orNull?.let { load(it.reader()) }
    }
    val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    uploadKeyMissing = signingKeys.filter { signing.getProperty(it).isNullOrEmpty() }
      .takeIf { it.isNotEmpty() }?.let { "release signing: no upload key ($signingPath lacks ${it.joinToString()}). Run tools/make-upload-key.sh" }
    if (uploadKeyMissing == null) {
      signingConfigs.create("upload") {
        storeFile = file(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
      }
    }

    buildTypes {
        debug {
            // Its own app next to the Play one: a debug build can't replace a Play-signed install without
            // uninstalling it, which would wipe the receipts (D113).
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_SUPABASE_URL", quoted(supabaseUrl))
            buildConfigField("String", "DEFAULT_SUPABASE_KEY", quoted(supabaseKey))
        }
        release {
            val ok = releaseServerProblem == null
            buildConfigField("String", "DEFAULT_SUPABASE_URL", quoted(if (ok) releaseUrl else ""))
            buildConfigField("String", "DEFAULT_SUPABASE_KEY", quoted(if (ok) releaseKey else ""))
            signingConfig = signingConfigs.findByName("upload")
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

    // Sanitized SEFAZ pages (tools/make-nfce-fixture.py), read by the JVM and the on-device tests.
    sourceSets["test"].resources.srcDir("src/test/fixtures")
    // The parity helper and goldens are shared with the on-device tests (Android's regex engine differs).
    sourceSets["test"].kotlin.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].kotlin.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].resources.srcDirs("src/test/fixtures", "src/test/resources")

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

// What goes to Play must be signed with the upload key (D105). assembleRelease may stay unsigned, for local checks.
tasks.matching { it.name == "bundleRelease" || it.name == "signReleaseBundle" }.configureEach {
  val missing: String = uploadKeyMissing ?: return@configureEach
  doFirst { throw GradleException(missing) }
}

// On-device timings of the frame analysis (camera plan, step 0): OCR and the code scanner over recorded frames, on the
// phone, before each release. A library module on purpose: its test APK (br.com.roqlab.caderneta.benchmark.test) holds
// everything it runs and is the only thing installed or removed, so running it never touches the app or its receipts.
// Run it with tools/device-bench.sh, not connectedAndroidTest.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.androidx.benchmark)
}

android {
  namespace = "br.com.roqlab.caderneta.benchmark"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
  }
  // Timings from a debuggable build are meaningless: the test APK is built as release.
  testBuildType = "release"
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  androidTestImplementation(libs.androidx.benchmark.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  // The same ML Kit models the app ships (bundled: no Play Services).
  androidTestImplementation(libs.mlkit.text.recognition)
  androidTestImplementation(libs.mlkit.barcode.scanning)
}

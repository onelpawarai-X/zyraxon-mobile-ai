plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.zyraxon.assistant"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  // Which CPU family this APK is for, chosen at build time with
  // `-PzyraxonAbi=arm64` or `-PzyraxonAbi=arm32`. Empty means a universal build
  // that carries both, which is what a local `assembleDebug` gets.
  //
  // Two things follow from it: only that family's native libraries are packed,
  // and only that family's Linux payload is packed. The payload is 165-183 MB
  // per architecture, so a universal APK made every phone carry a whole second
  // operating system it could never start.
  val zyraxonAbi = (findProperty("zyraxonAbi") as String?)?.trim()?.lowercase().orEmpty()

  defaultConfig {
    applicationId = "com.zyraxon.assistant"
    minSdk = 21
    // 28 on purpose, not an oversight: Android 10+ blocks execve of files in the
    // app's data dir, and the bundled Linux runs its binaries from there. Raising
    // this to 29+ silently breaks the whole Linux path. See AGENTS.md.
    targetSdk = 28
    versionCode = 55
    versionName = "6.24.5"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      when (zyraxonAbi) {
        "arm64" -> abiFilters += listOf("arm64-v8a")
        "arm32" -> abiFilters += listOf("armeabi-v7a")
        // A universal build leaves the filters alone, so it packs every native
        // library the project has — exactly what it did before this split.
        else -> {}
      }
    }
  }

  // Signing material comes from the environment so no key is ever committed.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    // JGit is built on `java.nio.file`, which is API 26+. The app supports API
    // 24, so those classes are desugared rather than left to crash on older
    // phones the moment `git clone` is typed.
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.systemProperty("zyraxon.wakeTimeoutMs", "3000")
        // A failing assertion must say what it saw. The default SHORT format
        // prints only "AssertionError at File.kt:line", which hides the actual
        // values and turns every failure into a guess.
        it.testLogging {
          events("failed")
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
      }
    }
  }
  // Robolectric loads its native runtime into a shared temp directory. Parallel
  // forks race on it (`FileSystemAlreadyExistsException`), so the suite runs in
  // one fork. Correctness over speed: 188 tests still finish in under a minute.
  tasks.withType<Test>().configureEach { maxParallelForks = 1 }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  // The terminal ships a static busybox per ABI under jniLibs. Android only lets
  // an app execute a binary that lives in the native library directory, so these
  // must be extracted to disk rather than mapped straight from the APK, and they
  // must not be stripped or AGP would rewrite the ELF header busybox needs.
  packaging {
    jniLibs {
      useLegacyPackaging = true
      keepDebugSymbols += "**/libbusybox.so"
    }
  }

  // The Linux payload under assets/linux/ is already compressed (zip and xz), so
  // storing it as-is skips a pointless second pass at build time and lets it be
  // streamed out of the APK without inflating the whole member into memory.
  androidResources {
    noCompress += listOf("zip", "deb", "xz", "gz", "Packages", "glb")
  }

  // Each CPU family's Linux payload lives in its own source set, and only the
  // one this APK is for is added as an asset directory. The payload is a whole
  // operating system — 165-183 MB per architecture — so a universal APK made
  // every phone carry two systems it could never start. A build with no
  // `zyraxonAbi` adds all of them, which keeps a plain `assembleDebug` and the
  // unit tests working exactly as before.
  sourceSets {
    getByName("main") {
      when (zyraxonAbi) {
        "arm64" -> assets.directories.add("src/payload64/assets")
        "arm32" -> assets.directories.add("src/payload32/assets")
        else -> {
          // A universal build — a local `assembleDebug`, the unit tests, or a
          // release built without the flag — carries every payload, exactly as
          // it did before this split existed.
          assets.directories.add("src/payload64/assets")
          assets.directories.add("src/payload32/assets")
          assets.directories.add("src/payloadx64/assets")
        }
      }
    }
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // registerForActivityResult needs a Fragment version that reports its result
  // correctly; without it lint fails the release build.
  implementation(libs.androidx.fragment.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  // On-device inference: runs a downloaded model (.task / Gemma) on the phone
  // itself, with no network at all. Optional at runtime — the app works without it.
  implementation(libs.mediapipe.tasks.genai)
  // LiteRT-LM: the runtime that Gemma 4 and Qwen3 (.litertlm) need. MediaPipe above
  // is in maintenance mode and stays only for older .task bundles.
  implementation(libs.litertlm.android)
  implementation(libs.okhttp)
  // Serves the 3D avatar page and models from assets to a WebView over a safe https origin.
  implementation(libs.androidx.webkit)
  // Real `git clone` in the terminal. busybox has no git, so the terminal would
  // otherwise be the one shell that cannot clone a repository.
  implementation(libs.jgit)
  implementation(libs.slf4j.nop)
  // System-level access the user opts into. Shizuku lets ZYRAXON run commands as
  // the `shell` user (uid 2000) or as root (uid 0) on a rooted phone, without
  // the app itself holding any privileged permission. It is the same mechanism
  // a system shell on Android uses.
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // On-device OCR for words drawn inside pictures, where the accessibility tree
  // has no element to read. Runs offline; nothing leaves the phone for it.
  implementation(libs.mlkit.text.recognition)
  implementation(libs.mlkit.text.recognition.devanagari)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.okhttp.mockwebserver)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

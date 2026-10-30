plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.zyraxon.assistant"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.zyraxon.assistant"
    minSdk = 21
    targetSdk = 36
    versionCode = 17
    versionName = "6.8.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
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
  implementation(libs.okhttp)
  // Real `git clone` in the terminal. busybox has no git, so the terminal would
  // otherwise be the one shell that cannot clone a repository.
  implementation(libs.jgit)
  implementation(libs.slf4j.nop)
  // System-level access the user opts into. Shizuku lets ZYRAXON run commands as
  // the `shell` user (uid 2000) or as root (uid 0) on a rooted phone, without
  // the app itself holding any privileged permission. It is the same mechanism
  // Termux and every other "real shell" app on Android uses.
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
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

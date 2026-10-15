import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version source of truth is version.properties. Every real assemble/bundle
// invocation bumps patch (1.0.0 -> 1.0.1) and versionCode by one, before the
// android block below consumes the values, so the APK just built already
// carries the new version in its manifest and file name.
val versionPropsFile = file("version.properties")
val versionProps = Properties().also {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use(it::load)
}
var appVersionCode = (versionProps.getProperty("VERSION_CODE") ?: "1").toInt()
var appVersionName = versionProps.getProperty("VERSION_NAME") ?: "1.0.0"
val isPackagingBuild = gradle.startParameter.taskNames.any {
    it.contains("assemble", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
} && !gradle.startParameter.isDryRun
// F-Droid / reproducible builds: `-PfdroidBuild` (or FDROID_BUILD=1) freezes
// the version from version.properties, disables the in-app GitHub update
// check, and builds native code from source only (no vendored ncnn/Halide
// archives; the emulator-tested stubs and Kotlin fallbacks take over).
val isFdroidBuild = project.hasProperty("fdroidBuild") || System.getenv("FDROID_BUILD") == "1"
if (isPackagingBuild && !isFdroidBuild) {
    appVersionName.split(".").toMutableList().let { parts ->
        parts.last().toIntOrNull()?.let { patch ->
            parts[parts.size - 1] = (patch + 1).toString()
            appVersionName = parts.joinToString(".")
        }
    }
    appVersionCode += 1
    versionProps.setProperty("VERSION_CODE", appVersionCode.toString())
    versionProps.setProperty("VERSION_NAME", appVersionName)
    versionPropsFile.writer().use { versionProps.store(it, "Bumped automatically on assemble/bundle") }
    println("RawLens version: $appVersionName ($appVersionCode)")
}

android {
    namespace = "com.matthew.rawlens"
    compileSdk = 35
    // Pinned so local, CI, and F-Droid builds use the same NDK.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.matthew.rawlens"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ENABLE_UPDATE_CHECK", if (isFdroidBuild) "false" else "true")
        // Store builds ship ARM only: x86/x86_64 exist solely for local
        // emulator testing, and from-source ncnn is ~18MB per x86 ABI.
        ndk {
            if (isFdroidBuild) {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }
        externalNativeBuild {
            cmake {
                if (isFdroidBuild) {
                    arguments("-DRAWLENS_PREBUILT_NATIVE=OFF")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("release") {
            // F-Droid builds strip the updater-only permissions via the
            // overlay manifest (merged over src/main/AndroidManifest.xml).
            if (isFdroidBuild) {
                manifest.srcFile("fdroid/AndroidManifest.xml")
            }
        }
    }

    signingConfigs {
        // Local performance/release testing signs with the debug key so no
        // secret is needed. CI/Play builds override via environment:
        // RAWLENS_KEYSTORE, RAWLENS_KEY_ALIAS, RAWLENS_KEYSTORE_PASSWORD,
        // RAWLENS_KEY_PASSWORD.
        create("releaseLocal") {
            val keystoreFile = System.getenv("RAWLENS_KEYSTORE")?.let { file(it) }
            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("RAWLENS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAWLENS_KEY_ALIAS")
                keyPassword = System.getenv("RAWLENS_KEY_PASSWORD")
            } else {
                val debugKey = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storeFile = debugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 code shrinking runs for F-Droid builds (store requirement;
            // JNI entry points and layout-inflated views are covered by
            // proguard-rules.pro). GitHub releases stay unobfuscated for
            // now so perf comparisons run on identical code.
            isMinifyEnabled = isFdroidBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("releaseLocal")
            // Full ART optimization at install (vs quicken+JIT for debuggable)
            // plus CMake Release (-O3) native code. Force complete AOT on the
            // device after install with:
            //   adb shell cmd package compile -m speed -f com.matthew.rawlens
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Merged-DNG writer unit tests serialize through the host TinyDNG
        // lib (same C sources as the Android rawLensDng target); on-device
        // code loads the APK's .so via System.loadLibrary instead.
        unitTests.all {
            it.systemProperty("rawlens.dnglib",
                System.getProperty("rawlens.dnglib") ?: dngTestLibFile.get().asFile.absolutePath)
        }
    }
    // On-device tests always run against release (CMake -O3): debug native
    // code is ~40x slower and cannot hold record-mode cadence, so debug
    // numbers never represent shipping performance.
    testBuildType = "release"

    // Optional on-device DCG probe library, built by tools/build_dcg_vulkan_probe.sh.
    sourceSets.getByName("androidTest").jniLibs.srcDir("build/dcg-probe/jniLibs")

    // Name APKs after the app instead of the generic module name, e.g.
    // RawLens-1.0.0-release.apk instead of app-release.apk.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "RawLens-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

}

// F-Droid builds must not ship the updater-only permissions (stripped by
// app/fdroid/AndroidManifest.xml). Fails the build if they survive the
// manifest merge, so a future manifest refactor cannot silently re-add
// them to store builds.
if (isFdroidBuild) {
    val checkFdroidPermissions by tasks.registering {
        description = "Fails if updater-only permissions survived the F-Droid manifest merge."
        doLast {
            val merged = layout.buildDirectory.dir("intermediates/merged_manifests/release")
                .get().asFile.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
                ?: error("checkFdroidPermissions: merged release manifest not found")
            val leaked = listOf(
                "android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES",
            ).filter { merged.readText().contains(it) }
            if (leaked.isNotEmpty()) {
                error("checkFdroidPermissions: stripped permissions still present: $leaked")
            }
        }
    }
    tasks.matching { it.name == "processReleaseMainManifest" }.configureEach {
        finalizedBy(checkFdroidPermissions)
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.core:core-ktx:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.21.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.core" && (requested.name == "core" || requested.name == "core-ktx")) {
            useVersion("1.9.0")
            because("pin for AGP 8.7.3 / compileSdk 35 (exifinterface 1.4.2 pulls core 1.18 which needs SDK36/AGP8.9)")
        }
    }
}

// Host librawLensDng for JVM unit tests (see also :tools:sr-vulkan,
// which stages the same lib for the desktop CLI). The script compiles
// the pinned TinyDNG sources plus the JNI bridge for the host.
val dngTestLibName = if ("mac" in System.getProperty("os.name").lowercase()) {
    "librawLensDng.dylib"
} else {
    "librawLensDng.so"
}
val dngTestLibDir = layout.buildDirectory.dir("dng-native")
val dngTestLibFile = dngTestLibDir.map { it.file(dngTestLibName) }
tasks.register<Exec>("buildDngNativeHost") {
    onlyIf { System.getenv("PATH").split(":").any { File(it, "cc").canExecute() } }
    val javaHome = System.getProperty("java.home")
    val plat = if ("mac" in System.getProperty("os.name").lowercase()) "darwin" else "linux"
    commandLine(
        "bash", rootDir.resolve("tools/build_dng_native.sh").absolutePath,
        dngTestLibFile.get().asFile.absolutePath,
        "$javaHome/include", "$javaHome/include/$plat"
    )
}
tasks.matching { it.name.startsWith("test") && it.name.contains("UnitTest") }.configureEach {
    dependsOn("buildDngNativeHost")
}

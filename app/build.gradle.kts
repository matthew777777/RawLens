plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matthew.rawlens"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matthew.rawlens"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // No minify/shrink: the app carries JNI entry points (ncnn,
            // amaze_reference, jpeg, vulkan) that R8 would need explicit
            // keep rules for, and perf comparisons stay on identical code.
            isMinifyEnabled = false
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
    }

    // Optional on-device DCG probe library, built by tools/build_dcg_vulkan_probe.sh.
    sourceSets.getByName("androidTest").jniLibs.srcDir("build/dcg-probe/jniLibs")

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

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

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/photonFlowNet/java"))
    // The pinned PhotonCamera checkout supplies FlowNet; DNG uses vendored TinyDNG v3.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/photonFlowNet/assets"))
}

val syncPhotonFlowNet by tasks.registering(Sync::class) {
    from(file("../references/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/ml/FlowNetNcnnProcessor.java"))
    filter { line: String ->
        line.replace("import com.particlesdevs.photoncamera.util.Log;", "import android.util.Log;")
    }
    into(layout.buildDirectory.dir(
        "generated/photonFlowNet/java/com/particlesdevs/photoncamera/processing/ml"
    ))
}

val syncPhotonFlowNetModels by tasks.registering(Sync::class) {
    from(file("../references/PhotonCamera/app/src/main/assets/models")) {
        include("flownet_flat.ncnn.param", "flownet_flat.ncnn.bin")
    }
    into(layout.buildDirectory.dir("generated/photonFlowNet/assets/models"))
}

tasks.named("preBuild").configure {
    dependsOn(syncPhotonFlowNet, syncPhotonFlowNetModels)
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.21.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

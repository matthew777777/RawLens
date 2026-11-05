import java.net.URI
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.util.Properties
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
}

abstract class CompileSrpShaders : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaderDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val sources = shaderDirectory.get().asFile.listFiles()
            .orEmpty().filter { it.extension == "comp" }.sortedBy { it.name }
        check(sources.isNotEmpty()) { "No SR pipeline shader sources found" }
        val destination = outputDirectory.get().dir("sr_spv").asFile
        destination.mkdirs()
        for (source in sources) {
            execOperations.exec {
                commandLine(
                    compiler.get().asFile.absolutePath,
                    "--target-env=vulkan1.0", "-fshader-stage=compute",
                    source.absolutePath, "-o", destination.resolve("${source.nameWithoutExtension}.spv")
                )
            }
        }
    }
}

// Use the pinned Android NDK's compiler: a fresh checkout needs neither
// a desktop Vulkan SDK nor precompiled files in the host proof build tree.
val compileSrpShaders by tasks.registering(CompileSrpShaders::class) {
    shaderDirectory.set(rootProject.layout.projectDirectory.dir("tools/sr-pipeline/src/main/cpp/shaders"))
    val host = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    val executable = if (host.startsWith("windows")) "glslc.exe" else "glslc"
    compiler.set(androidComponents.sdkComponents.ndkDirectory.map {
        it.file("shader-tools/$host/$executable")
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/srpspv"))
}

abstract class CompilePhotoRcdShader : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val upstreamLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generators: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations
    @TaskAction fun compile() {
        val root = outputDirectory.get().asFile
        val shaders = root.resolve("shaders/photo_rcd").apply { mkdirs() }
        execOperations.exec {
            commandLine("python3", source.get().asFile.parentFile.resolve("write_app_shaders.py").absolutePath,
                shaders.absolutePath, compiler.get().asFile.absolutePath)
        }
        val licenses = root.resolve("licenses/photo-rcd").apply { mkdirs() }
        upstreamLicense.get().asFile.copyTo(licenses.resolve("GPL-3.0.txt"), overwrite = true)
        notice.get().asFile.copyTo(licenses.resolve("NOTICE.txt"), overwrite = true)
    }
}
val compilePhotoRcdShader by tasks.registering(CompilePhotoRcdShader::class) {
    source.set(rootProject.layout.projectDirectory.file("tools/photo-rcd/baseline.comp"))
    upstreamLicense.set(rootProject.layout.projectDirectory.file("tools/photo-rcd/upstream/RawTherapee/LICENSE"))
    notice.set(rootProject.layout.projectDirectory.file("tools/photo-rcd/NOTICE.txt"))
    generators.from("${rootProject.projectDir}/tools/photo-rcd/write_app_shaders.py",
        "${rootProject.projectDir}/tools/photo-rcd/build_variants.py",
        "${rootProject.projectDir}/tools/photo-rcd/requirements_input_shaders.py",
        "${rootProject.projectDir}/tools/photo-rcd/tile_local.py",
        "${rootProject.projectDir}/tools/photo-rcd/frame_global/generate.py",
        "${rootProject.projectDir}/tools/photo-rcd/frame_global/unroll.py",
        "${rootProject.projectDir}/tools/photo-rcd/frame_global/rcd_frame_walk.comp.in",
        "${rootProject.projectDir}/tools/photo-rcd/raw_cell_pack.comp",
        "${rootProject.projectDir}/tools/photo-rcd/build_oracle.py",
        "${rootProject.projectDir}/tools/photo-rcd/prepare_recording_candidate.py",
        "${rootProject.projectDir}/tools/rcd-baseline/fast-20261026/vf_rcd_measured_200ms.comp")
    compiler.set(compileSrpShaders.flatMap { it.compiler })
    outputDirectory.set(layout.buildDirectory.dir("generated/photo-rcd-assets"))
}

// Version source of truth is version.properties. Every real assemble/bundle
// invocation bumps patch (2.0.99 -> 2.1.0) and versionCode by one, before the
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
// F-Droid builds the fdroid flavor (`assembleFdroidRelease`, recipe
// `gradle: [fdroid]`). The flavor itself carries the code, manifest and
// native differences (productFlavors below); the two things AGP cannot
// vary per flavor, ABI splits and the version auto-bump, key off the
// requested tasks instead: an fdroid build freezes the version from
// version.properties and packages exactly one universal APK.
val isFdroidBuild = gradle.startParameter.taskNames.any { it.contains("fdroid", ignoreCase = true) }
// `-PfreezeVersion` builds a tagged release at exactly the committed version
// (no auto-bump), like F-Droid does.
val freezeVersion = isFdroidBuild || project.hasProperty("freezeVersion")
if (isPackagingBuild && !freezeVersion) {
    val parts = appVersionName.split(".").map(String::toInt)
    require(parts.size == 3 && parts.all { it >= 0 } && parts[2] <= 99) {
        "VERSION_NAME must be major.minor.patch with patch between 0 and 99"
    }
    val (major, minor, patch) = parts
    appVersionName = if (patch == 99) "$major.${minor + 1}.0"
        else "$major.$minor.${patch + 1}"
    appVersionCode += 1
    versionProps.setProperty("VERSION_CODE", appVersionCode.toString())
    versionProps.setProperty("VERSION_NAME", appVersionName)
    versionPropsFile.writer().use { versionProps.store(it, "Bumped automatically on assemble/bundle") }
    println("RawLens version: $appVersionName ($appVersionCode)")
}

// Qualcomm JIT needs both LiteRT plugins and the matching QAIRT SDK libraries.
// Enabled on every checkout; Gradle fetches missing dependencies automatically.
val machineProperties = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(props::load)
}
fun localBuildProperty(name: String) = providers.gradleProperty(name).orElse(
    providers.provider { machineProperties.getProperty(name) }
)
val qualcommNpu = localBuildProperty("qualcommNpu").map { it.toBooleanStrict() }.getOrElse(true)
val qairtSdkOverride = localBuildProperty("qairtSdk")
val qairtSdk = qairtSdkOverride.orElse("../.local/qualcomm/2.50.0.260828")
val fetchQualcommSdk = tasks.register("fetchQualcommSdk") {
    val destination = file(qairtSdk.get())
    val libraries = listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so")
        .map { "lib/aarch64-android/$it" } + listOf(69, 73, 75, 79, 81).flatMap {
            listOf("lib/aarch64-android/libQnnHtpV${it}Stub.so",
                "lib/hexagon-v${it}/unsigned/libQnnHtpV${it}Skel.so")
        }
    outputs.files(libraries.map { destination.resolve(it) })
    onlyIf { qualcommNpu && libraries.any { !destination.resolve(it).isFile } }
    doLast {
        check(!qairtSdkOverride.isPresent) { "Incomplete custom QAIRT SDK: $destination" }
        check(!gradle.startParameter.isOffline) {
            "QAIRT SDK is not cached. Run the build once without --offline."
        }
        destination.parentFile.mkdirs()
        val archive = File.createTempFile("qairt-", ".zip", destination.parentFile)
        try {
            logger.lifecycle("Downloading QAIRT 2.50.0.260828 (approximately 2.4 GB; first build only)")
            val url = URI("https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip").toURL()
            val connection = url.openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
            connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            ZipFile(archive).use { zip ->
                for (path in libraries) {
                    val entry = zip.getEntry("qairt/2.50.0.260828/$path")
                        ?: error("QAIRT archive is missing $path")
                    val target = destination.resolve(path)
                    target.parentFile.mkdirs()
                    val partial = File.createTempFile("qnn-", ".part", target.parentFile)
                    try {
                        zip.getInputStream(entry).use { input -> partial.outputStream().use { input.copyTo(it) } }
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally { partial.delete() }
                }
            }
        } finally { archive.delete() }
    }
}
val prepareQualcommRuntime by tasks.registering(Sync::class) {
    dependsOn(fetchQualcommSdk)
    val sdk = qairtSdk.map { file(it) }
    val hostLibraries = listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so")
    val architectures = listOf(69, 73, 75, 79, 81)
    val paths = hostLibraries.map { "lib/aarch64-android/$it" } +
        architectures.flatMap { listOf(
            "lib/aarch64-android/libQnnHtpV${it}Stub.so",
            "lib/hexagon-v${it}/unsigned/libQnnHtpV${it}Skel.so"
        ) }
    if (qualcommNpu) {
        require(qairtSdk.isPresent) { "Qualcomm NPU requires -PqairtSdk=/path/to/qairt/2.50.0.260828" }
        from(sdk) {
            include(paths)
            eachFile { path = "arm64-v8a/$name" }
            includeEmptyDirs = false
        }
        doFirst {
            val missing = paths.filter { !sdk.get().resolve(it).isFile }
            check(missing.isEmpty()) { "Incomplete QAIRT 2.50.0.260828 SDK: $missing" }
        }
    }
    into(layout.buildDirectory.dir("generated/qualcomm-jni"))
}

if (qualcommNpu) {
    tasks.named("preBuild").configure { dependsOn(prepareQualcommRuntime) }
}

android {
    if (qualcommNpu) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/qualcomm-jni").get().asFile)
        // Hexagon ELF files are DSP firmware, not ARM libraries: never strip them.
        packaging.jniLibs.keepDebugSymbols.add("**/libQnnHtpV*Skel.so")
    }
    // LiteRT loads its vendor dispatch / compiler plugins from a real
    // directory (nativeLibraryDir): extract native libraries on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    namespace = "com.matthew.rawlens"
    compileSdk = 37
    // Pinned so local, CI, and F-Droid builds use the same NDK.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.matthew.rawlens"
        // Floor is Android 11: the viewfinder (RGBA8) and stills
        // (GLES/CPU) pipelines run there, and the startup VulkanSupport
        // gate informs users whose device fails it instead of failing
        // silently. The GPU record/RCD path additionally needs RGBA_FP16
        // HardwareBuffers (Android 13+); those call sites guard with
        // VulkanSupport.isFp16BufferSupported and fall back with a
        // user-visible status. Do not raise without re-checking.
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ARM-only app: every build (local, CI, store) ships arm64-v8a +
        // armeabi-v7a. x86/x86_64 emulator ABIs are excluded, so native
        // code (libjpeg-turbo, ncnn, Halide filters) is never compiled
        // for them. (This used to be F-Droid-only; from-source ncnn is
        // ~18MB per extra ABI, and the x86 libjpeg-turbo SIMD build
        // needs nasm, which dev machines often lack.)
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
        externalNativeBuild {
            cmake {
                // Force true CMake Release (-O3 -DNDEBUG, no debug info)
                // for every build type. AGP otherwise uses RelWithDebInfo
                // (-O2 -g) even for the release build type.
                arguments("-DCMAKE_BUILD_TYPE=Release")
                // JXL source: -PjxlSourceDir=<libjxl checkout> wins; else the
                // pinned third_party/libjxl submodule when checked out (F-Droid
                // builds it from there with submodules: true, no network);
                // else CMake fetches the pinned release. -PdisableJxl builds
                // the "not bundled" stub (offline builds).
                val jxlSubmodule = rootProject.file("third_party/libjxl")
                val jxlSource = project.findProperty("jxlSourceDir")?.toString()
                    ?: jxlSubmodule.takeIf { it.resolve("CMakeLists.txt").isFile }?.absolutePath
                if (jxlSource != null) {
                    arguments("-DLIBJXL_SOURCE_DIR=$jxlSource")
                }
                if (project.hasProperty("disableJxl")) {
                    arguments("-DRAWLENS_ENABLE_JXL=OFF")
                }
            }
        }
    }

    // Distribution channel. github (default): GitHub releases with the
    // in-app updater; its INTERNET / REQUEST_INSTALL_PACKAGES permissions
    // and FileProvider live in src/github/AndroidManifest.xml only.
    // fdroid: the F-Droid build: updater compiled out, no updater
    // permissions, native code from source only (no vendored ncnn/Halide
    // archives; ncnn from the pinned source tarball, Kotlin/legacy
    // fallbacks for the Halide filters). See docs/fdroid.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "false")
            externalNativeBuild {
                cmake {
                    arguments("-DRAWLENS_PREBUILT_NATIVE=OFF")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Per-ABI APK splits plus a universal APK. Devices download/install
    // only their ABI's native libraries (roughly half the native payload
    // of the universal APK); the universal APK remains the fallback the
    // older links expect. F-Droid builds (fdroid tasks requested, see
    // isFdroidBuild) disable splits: that channel ships exactly one
    // universal APK, and fdroidserver rejects more than one.
    splits {
        abi {
            isEnable = !isFdroidBuild
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // CI/Play builds sign with a real key via environment:
        // RAWLENS_KEYSTORE, RAWLENS_KEY_ALIAS, RAWLENS_KEYSTORE_PASSWORD,
        // RAWLENS_KEY_PASSWORD. A RAWLENS_KEYSTORE path that does not exist
        // fails fast here instead of silently shipping a wrongly-signed
        // artifact. Everyone else falls back to the standard debug key
        // (see the release build type below), which the Android Gradle
        // plugin generates on demand, so a fresh checkout builds and
        // installs with no secret needed. (Pointing a custom signing config
        // at ~/.android/debug.keystore by hand broke fresh clones: that
        // file only exists after a debug build, so the very first
        // assembleRelease failed with "Keystore file ... not found".)
        val keystorePath = System.getenv("RAWLENS_KEYSTORE")
        if (!keystorePath.isNullOrBlank()) {
            create("releaseEnv") {
                val keystoreFile = file(keystorePath)
                if (!keystoreFile.exists()) {
                    error("RAWLENS_KEYSTORE points at a missing file: $keystorePath")
                }
                storeFile = keystoreFile
                storePassword = System.getenv("RAWLENS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAWLENS_KEY_ALIAS")
                keyPassword = System.getenv("RAWLENS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 for every release (GitHub and F-Droid ship the same code):
            // dex 9.8 -> 1.4 MB. JNI entry points and layout-inflated views
            // are covered by proguard-rules.pro; enum names (stored in
            // preferences) survive as string constants. The mapping is
            // archived per version (archiveReleaseMapping below) so crash
            // stack traces can be retraced.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Resource shrinking on: no getIdentifier/dynamic resource
            // lookups exist (audited 2026-10), so R8 can safely drop
            // unreferenced resources. Keep rules live in
            // res/raw/keep.xml if that ever changes.
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // Signed with RAWLENS_KEYSTORE when provided, otherwise with the
            // auto-generated debug key: release stays installable straight
            // from a fresh clone (see signingConfigs above). Keep the
            // assignment a single-token line: fdroidserver deletes
            // `signingConfig = <token>` lines (and the signingConfigs block)
            // before building, so F-Droid gets an unsigned APK. A wrapped
            // `?:` expression would leave its second half behind, attached
            // to the line above, and break the script.
            val releaseSigning = signingConfigs.findByName("releaseEnv")
                ?: signingConfigs.getByName("debug")
            signingConfig = releaseSigning
            // Full ART optimization at install (vs quicken+JIT for debuggable)
            // plus CMake Release (-O3) native code. Force complete AOT on the
            // device after install with:
            //   adb shell cmd package compile -m speed -f com.matthew.rawlens
        }
        // Instrumented tests: release in every respect except R8. The device
        // tests reflect on private controller fields by name (soak, refactor
        // checks), which renaming would break; the minified APK itself gets
        // a smoke run per release instead.
        create("deviceTest") {
            initWith(getByName("release"))
            // Isolated opt-in package for synthetic headless RCD correctness;
            // installs cannot replace the user's normal camera application.
            if (project.hasProperty("photoRcdHeadless")) applicationIdSuffix = ".rcdcheck"
            if (project.hasProperty("mhcHeadless")) applicationIdSuffix = ".mhccheck"
            if (project.hasProperty("recordingToolsAudit")) applicationIdSuffix = ".recordingcheck"
            if (project.hasProperty("waveletCheck")) applicationIdSuffix = ".waveletcheck"
            if (project.hasProperty("lkrCheck")) applicationIdSuffix = ".lkrcheck"
            if (project.hasProperty("ffdnetCheck")) applicationIdSuffix = ".ffdnetcheck"
            isMinifyEnabled = false
            // Resource shrinking requires code shrinking (AGP enforces
            // the pair); tests keep the full resource set instead.
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Merged-DNG writer unit tests serialize through the host TinyDNG
        // lib (same C sources as the Android rawLensDng target); on-device
        // code loads the APK's .so via System.loadLibrary instead.
        unitTests.all {
            // An explicit -Drawlens.dnglib=<path> is honored verbatim (a
            // missing file then fails loudly at System.load). Otherwise the
            // just-built host lib is passed only when it exists: on machines
            // without a C compiler the build step is skipped, and handing
            // tests a nonexistent path would only produce a confusing load
            // error (native-serialization tests skip themselves instead).
            val explicitLib = System.getProperty("rawlens.dnglib")
            val builtLib = dngTestLibFile.get().asFile
            if (explicitLib != null) {
                it.systemProperty("rawlens.dnglib", explicitLib)
            } else if (builtLib.isFile) {
                it.systemProperty("rawlens.dnglib", builtLib.absolutePath)
            }
        }
    }
    // On-device tests always run against release (CMake -O3): debug native
    // code is ~40x slower and cannot hold record-mode cadence, so debug
    // numbers never represent shipping performance.
    testBuildType = "deviceTest"


    // Name APKs after the app instead of the generic module name, e.g.
    // RawLens-1.0.0-release.apk instead of app-release.apk. ABI splits
    // carry their ABI: RawLens-1.0.0-release-arm64-v8a.apk. Split
    // version codes sit above the universal APK's so a device that can
    // run a split always picks it (arm64 > armeabi-v7a > universal).
    applicationVariants.all {
        val variant = this
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = out.getFilter(com.android.build.OutputFile.ABI)
            if (abi == null) {
                out.outputFileName =
                    "RawLens-${variant.versionName}-${variant.buildType.name}.apk"
            } else {
                out.outputFileName =
                    "RawLens-${variant.versionName}-${variant.buildType.name}-$abi.apk"
            }
        }
    }

}

// R8 mapping per GitHub release: RawLens-<version>-release-mapping.txt.gz
// next to the githubRelease APK (attach it to the GitHub release) and a copy in release-mappings/
// at the repo root, which `clean` never touches. Retrace a crash with
//   retrace RawLens-<version>-release-mapping.txt stacktrace.txt
// (Android SDK cmdline-tools / R8).
val archiveReleaseMapping by tasks.registering {
    description = "Saves the release R8 mapping (gzip) next to the APK and in release-mappings/."
    val mappingFile = layout.buildDirectory.file("outputs/mapping/githubRelease/mapping.txt")
    val apkDir = layout.buildDirectory.dir("outputs/apk/github/release")
    val archiveDir = rootProject.layout.projectDirectory.dir("release-mappings")
    val name = "RawLens-$appVersionName-release-mapping.txt.gz"
    inputs.file(mappingFile)
    outputs.file(apkDir.map { it.file(name) })
    doLast {
        val src = mappingFile.get().asFile
        val targets = listOf(apkDir.get().file(name).asFile, archiveDir.file(name).asFile)
        for (target in targets) {
            target.parentFile.mkdirs()
            GZIPOutputStream(target.outputStream()).use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
        println("R8 mapping saved: ${targets.joinToString { it.path }}")
    }
}
tasks.matching { it.name == "assembleGithubRelease" || it.name == "bundleGithubRelease" }.configureEach {
    finalizedBy(archiveReleaseMapping)
}

// The fdroid flavor must not ship the updater-only permissions (declared
// only in src/github/AndroidManifest.xml). Fails the build if they reach
// the merged fdroid manifest, so a future manifest refactor (or a library
// that declares them) cannot silently re-add them to F-Droid builds.
val checkFdroidPermissions by tasks.registering {
    description = "Fails if updater-only permissions reached the fdroid release manifest."
    val mergedDir = layout.buildDirectory
        .dir("intermediates/merged_manifest/fdroidRelease/processFdroidReleaseMainManifest")
    doLast {
        // The output of processFdroidReleaseMainManifest, the task this
        // check finalizes. (merged_manifests/fdroidRelease is written later
        // by processFdroidReleaseManifest: stale or absent at this point.)
        val merged = mergedDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "AndroidManifest.xml" }
            ?: error("checkFdroidPermissions: merged fdroid release manifest not found")
        val leaked = listOf(
            "android.permission.INTERNET",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "androidx.core.content.FileProvider",
        ).filter { merged.readText().contains(it) }
        if (leaked.isNotEmpty()) {
            error("checkFdroidPermissions: updater-only entries in the fdroid manifest: $leaked")
        }
    }
}
tasks.matching { it.name == "processFdroidReleaseMainManifest" }.configureEach {
    finalizedBy(checkFdroidPermissions)
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    // LK-Reloaded FFDNet prior on the NPU (tools/lkr-pipeline lkr_litert.cpp,
    // dlopen'ed: unsupported devices fall back to ncnn).
    // Only the AARs' native libraries are used (never the LiteRT Java API), so
    // no transitive deps: litert pulls WorkManager + Room, whose R8-stripped
    // WorkDatabase crashed release startup (androidx.startup provider).
    implementation("com.google.ai.edge.litert:litert:2.3.2") { isTransitive = false }
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.2") { isTransitive = false }
    if (qualcommNpu) {
        val cachedRuntime = rootProject.file(".local/qualcomm/litert-npu-runtime-qualcomm-2.3.2.aar")
        val localRuntime = localBuildProperty("qualcommRuntimeAar").orElse(
            providers.provider { cachedRuntime.takeIf { it.isFile }?.absolutePath }
        )
        if (localRuntime.isPresent) {
            val aar = file(localRuntime.get())
            require(aar.isFile) { "Missing Qualcomm runtime AAR: $aar. Run python3 tools/setup_qualcomm.py from the repository root." }
            implementation(files(aar))
        } else {
            implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2") { isTransitive = false }
        }
    }
    // Unified SR pipeline (Kotlin stages + SrpBridge JNI facade, single
    // truth with tools/sr-pipeline; its native half builds as
    // libsrpipeline.so from the same sources via app CMake).
    implementation(project(":tools:sr-pipeline"))
    implementation("androidx.media3:media3-muxer:1.10.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.core:core-ktx:1.19.1")
    // Installs the Baseline Profile (src/main/baseline-prof.txt) at
    // first launch so startup code is AOT-compiled instead of JIT'd.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Register the producer with AGP so every variant's asset consumers wait
// for shader compilation, including packaging and lint on clean builds.
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        compilePhotoRcdShader, CompilePhotoRcdShader::outputDirectory
    )
    variant.sources.assets?.addGeneratedSourceDirectory(
        compileSrpShaders, CompileSrpShaders::outputDirectory
    )
    // Split version codes above the universal APK's (see splits block):
    // a device that can run a split always picks it over the universal.
    for (output in variant.outputs) {
        val abi = output.filters.find {
            it.filterType ==
                com.android.build.api.variant.FilterConfiguration.FilterType.ABI
        }?.identifier ?: continue
        output.versionCode.set(
            appVersionCode + when (abi) {
                "arm64-v8a" -> 2
                "armeabi-v7a" -> 1
                else -> 0
            }
        )
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.core" && (requested.name == "core" || requested.name == "core-ktx")) {
            useVersion("1.9.0")
            because("runtime parity with 1.x releases: every shipped build ran on core 1.9.0; raising it is a separate, device-tested change")
        }
    }
}

// Host librawLensDng for JVM unit tests (built by :app:buildDngNativeHost
// via tools/build_dng_native.sh; tests receive it through the
// rawlens.dnglib system property above when the file exists). The script
// compiles the pinned TinyDNG sources plus the JNI bridge for the host.
val hostOsName = System.getProperty("os.name").lowercase()
val isWindowsHost = "win" in hostOsName
val dngTestLibName = when {
    "mac" in hostOsName -> "librawLensDng.dylib"
    isWindowsHost -> "rawLensDng.dll"
    else -> "librawLensDng.so"
}
val dngTestLibDir = layout.buildDirectory.dir("dng-native")
val dngTestLibFile = dngTestLibDir.map { it.file(dngTestLibName) }
fun hasHostTool(name: String): Boolean {
    // File.pathSeparator, not a hardcoded ':', so PATH splits on Windows
    // (';') as well as Unix (':').
    val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
    return dirs.any { dir ->
        File(dir, name).canExecute() ||
            (isWindowsHost && File(dir, "$name.exe").canExecute())
    }
}
tasks.register<Exec>("buildDngNativeHost") {
    // Skipped (not failed) where no toolchain exists: the native
    // serialization tests skip themselves, and everything else stays green.
    onlyIf { hasHostTool("cc") && hasHostTool("bash") }
    val javaHome = System.getProperty("java.home")
    val plat = when {
        "mac" in hostOsName -> "darwin"
        isWindowsHost -> "win32"
        else -> "linux"
    }
    commandLine(
        "bash", rootDir.resolve("tools/build_dng_native.sh").absolutePath,
        dngTestLibFile.get().asFile.absolutePath,
        "$javaHome/include", "$javaHome/include/$plat"
    )
}
tasks.matching { it.name.startsWith("test") && it.name.contains("UnitTest") }.configureEach {
    dependsOn("buildDngNativeHost")
}

// Fresh-clone regression guard: release signing must work on a machine that
// has never built before (no ~/.android/debug.keystore). Runs a nested,
// daemonless :app:validateSigningRelease with user.home redirected to a
// blank directory, so the only keystore AGP can use is one it generates
// itself. Portable: runs anywhere Gradle runs (Linux, macOS, Windows) via
// the wrapper script for the host OS. Not wired into check (it spawns a
// full nested configuration), so run it explicitly:
//   ./gradlew :app:verifyFreshCloneSigning
tasks.register<Exec>("verifyFreshCloneSigning") {
    description = "Fails if release signing needs a pre-existing keystore (fresh-clone regression)."
    group = "verification"
    val freshHome = layout.buildDirectory.dir("fresh-home-check").get().asFile
    doFirst {
        freshHome.deleteRecursively()
        freshHome.mkdirs()
    }
    workingDir(rootDir)
    // Blank the home dir at BOTH layers: the JVM property and the process
    // environment. Different dev envs resolve "home" through different
    // mechanisms (user.home property vs $HOME on Unix vs %USERPROFILE% on
    // Windows, in the JVM as well as in native tooling), so overriding only
    // one could leave the nested build staring at the real ~/.android with
    // its pre-existing debug.keystore, passing vacuously. With both blank,
    // whatever the platform consults, it finds an empty home.
    environment("HOME", freshHome.absolutePath)
    environment("USERPROFILE", freshHome.absolutePath)
    val nestedArgs = listOf(
        ":app:validateSigningRelease",
        "--no-daemon", // isolate: neither layer must leak into this build's daemon
        "--console=plain",
        "-Duser.home=${freshHome.absolutePath}",
        "--gradle-user-home", gradle.gradleUserHomeDir.absolutePath
    )
    if (isWindowsHost) {
        // ProcessBuilder cannot launch .bat directly; go through cmd.
        commandLine("cmd", "/c", (listOf(rootDir.resolve("gradlew.bat").absolutePath) + nestedArgs)
            .joinToString(" ") { "\"$it\"" })
    } else {
        commandLine(listOf(rootDir.resolve("gradlew").absolutePath) + nestedArgs)
    }
}

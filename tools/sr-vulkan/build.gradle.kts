import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The ported Java ML wrapper lives beside the Kotlin sources (the parity
// layout mirrors the phone tree); javac must scan that root too, because
// kotlinc only resolves .java for symbols and never emits the class.
// Without this the wrapper silently stays analytic-only at runtime.
sourceSets {
    main {
        java {
            srcDir("src/main/kotlin")
        }
    }
}

// Unified SR merging library: the phone's SR core compiled 1:1 for the JVM
// (see PARITY.md) plus the Vulkan compute backend shared by Android (NDK)
// and desktop (Linux/macOS). Stdlib only at runtime; JUnit for tests.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Mechanical 1:1 guarantee: every ported file must be byte-identical to its
// phone original except the allowlisted host-boundary hunks (see
// tools/parity_sr_vulkan.py). Runs as part of `check`.
tasks.register<Exec>("parityCheck") {
    group = "verification"
    description = "Verify sr-vulkan sources are 1:1 with the phone originals."
    workingDir = rootDir
    commandLine("python3", "tools/parity_sr_vulkan.py")
}

tasks.register<Exec>("spirvCheck") {
    group = "verification"
    description = "Verify checked-in app SPIR-V matches a fresh transform."
    inputs.file("${rootDir}/tools/srvk_shader_transform.py")
    inputs.dir("${rootDir}/app/src/main/assets/shaders")
    inputs.dir("${rootDir}/app/src/main/assets/spirv")
    workingDir = rootDir
    commandLine("python3", "tools/srvk_spirv_check.py")
}

tasks.named("check") {
    dependsOn("parityCheck", "spirvCheck")
}

// ---- Unified native lib + SPIR-V packaging (desktop) ----------------------
// The same C++ sources build for Android via the NDK (see CMakeLists.txt).
// Native pieces are best-effort here: when the toolchain is absent the
// tasks skip loudly and the CPU paths keep working; `vkcheck` then fails
// with setup instructions instead of a cryptic UnsatisfiedLinkError.
fun commandExists(name: String): Boolean =
    System.getenv("PATH").split(":").any { File(it, name).canExecute() }

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val isMac = "mac" in hostOs
val nativePlatDir = when {
    isMac && ("aarch64" in hostArch || "arm64" in hostArch) -> "macos-arm64"
    isMac -> "macos-x64"
    "aarch64" in hostArch -> "linux-arm64"
    else -> "linux-x64"
}
// NOTE: keep in sync with SrVulkan.platformResource().
val nativeLibName = if (isMac) "libsrvulkan.dylib" else "libsrvulkan.so"

fun vulkanHeadersPresent(): Boolean {
    val sdk = System.getenv("VULKAN_SDK")
    if (sdk != null && File(sdk, "include/vulkan/vulkan.h").isFile) return true
    return File("/opt/homebrew/include/vulkan/vulkan.h").isFile ||
        File("/usr/include/vulkan/vulkan.h").isFile ||
        File("/usr/local/include/vulkan/vulkan.h").isFile
}

tasks.register("checkNativePrereqs") {
    group = "build"
    description = "Warn about missing native/Vulkan toolchain pieces."
    doLast {
        if (!commandExists("cmake")) logger.warn("sr-vulkan: cmake not found; native lib skipped")
        if (!commandExists("glslangValidator")) logger.warn("sr-vulkan: glslangValidator not found; SPIR-V skipped")
        if (!commandExists("python3")) logger.warn("sr-vulkan: python3 not found; ncnnMl skipped")
        if (!vulkanHeadersPresent()) logger.warn("sr-vulkan: Vulkan headers not found; native lib skipped")
    }
}

val spirvOut = layout.buildDirectory.dir("spirv")
tasks.register<Exec>("compileShaders") {
    group = "build"
    description = "Transform ESSL shaders to Vulkan SPIR-V + manifest."
    dependsOn("checkNativePrereqs")
    onlyIf { commandExists("glslangValidator") }
    inputs.dir("src/main/resources/shaders")
    inputs.file("${rootDir}/tools/srvk_shader_transform.py")
    outputs.dir(spirvOut)
    workingDir = rootDir
    commandLine(
        "python3", "tools/srvk_shader_transform.py",
        "--shaders", "tools/sr-vulkan/src/main/resources/shaders",
        "--out", spirvOut.get().asFile.absolutePath
    )
}

val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeLibFile = nativeBuildDir.map { it.file(nativeLibName) }
tasks.register<Exec>("cmakeConfigure") {
    group = "build"
    description = "Configure the unified srvulkan native library."
    dependsOn("checkNativePrereqs")
    onlyIf { commandExists("cmake") && vulkanHeadersPresent() }
    inputs.dir("src/main/cpp")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    commandLine(
        "cmake", "-S", "src/main/cpp", "-B", nativeBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release"
    )
}

tasks.register<Exec>("buildNative") {
    group = "build"
    description = "Build the unified srvulkan native library."
    dependsOn("cmakeConfigure")
    onlyIf { commandExists("cmake") && vulkanHeadersPresent() }
    inputs.dir("src/main/cpp")
    outputs.file(nativeLibFile)
    workingDir = projectDir
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.absolutePath, "-j", "8")
}

tasks.register<Copy>("stageNative") {
    dependsOn("buildNative")
    onlyIf { nativeLibFile.get().asFile.isFile }
    from(nativeLibFile)
    into(layout.buildDirectory.dir("resources/main/native/$nativePlatDir"))
}

tasks.register<Copy>("stageSpirv") {
    dependsOn("compileShaders")
    onlyIf { spirvOut.get().asFile.isDirectory }
    from(spirvOut)
    into(layout.buildDirectory.dir("resources/main/spirv"))
}

// ---- Host ncnnMl (ML inference: KernelNet/FlowNet/RawNIND) -----------------
// Same app JNI bridge + custom layers compiled against upstream ncnn
// (pinned in src/main/cpp-ncnn/CMakeLists.txt). Needs network once per
// clean build dir for the ~15MB ncnn sources plus a system glslang;
// pass -PsrvkSkipNcnn to skip (callers fall back to analytic kernels).
val skipNcnn = project.hasProperty("srvkSkipNcnn")
val ncnnLibName = if (isMac) "libncnnMl.dylib" else "libncnnMl.so"
val ncnnBuildDir = layout.buildDirectory.dir("ncnn-native")
val ncnnLibFile = ncnnBuildDir.map { it.file(ncnnLibName) }
val ncnnNativeDir = layout.buildDirectory.dir("resources/main/native/$nativePlatDir")

tasks.register<Exec>("cmakeConfigureNcnn") {
    group = "build"
    description = "Configure the host ncnnMl library (fetches upstream ncnn)."
    dependsOn("checkNativePrereqs")
    onlyIf { !skipNcnn && commandExists("cmake") && commandExists("python3") && vulkanHeadersPresent() }
    inputs.dir("src/main/cpp-ncnn")
    inputs.file("${rootDir}/app/src/main/cpp/ncnnMl.cpp")
    outputs.dir(ncnnBuildDir)
    workingDir = projectDir
    commandLine(
        "cmake", "-S", "src/main/cpp-ncnn", "-B", ncnnBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release"
    )
}

tasks.register<Exec>("buildNcnn") {
    group = "build"
    description = "Build the host ncnnMl library."
    dependsOn("cmakeConfigureNcnn")
    onlyIf { !skipNcnn && commandExists("cmake") && vulkanHeadersPresent() }
    inputs.dir("src/main/cpp-ncnn")
    inputs.file("${rootDir}/app/src/main/cpp/ncnnMl.cpp")
    outputs.file(ncnnLibFile)
    workingDir = projectDir
    commandLine("cmake", "--build", ncnnBuildDir.get().asFile.absolutePath, "-j", "8")
}

tasks.register<Copy>("stageNcnn") {
    dependsOn("buildNcnn")
    onlyIf { ncnnLibFile.get().asFile.isFile }
    from(ncnnLibFile)
    into(ncnnNativeDir)
}

tasks.named("processResources") {
    dependsOn("stageNative", "stageSpirv", "stageNcnn")
}

// GPU + staging tests need the built native lib, SPIR-V, and staged ESSL.
tasks.named<Test>("test") {
    dependsOn("processResources", "compileShaders")
    // The ported ML processors loadLibrary("ncnnMl") (dedup-by-name only),
    // so the staged host lib must sit on java.library.path (see NcnnLoader).
    systemProperty("java.library.path", ncnnNativeDir.get().asFile.absolutePath)
    // Full-grid Vulkan dispatch (unset = phone watchdog budgets; omit the
    // flag to exercise the phone's sliced schedule on desktop).
    systemProperty("srvk.sliceBudget", Int.MAX_VALUE.toString())
    // Forward the parity debug hook (-Dncnn.dump=... on the Gradle command).
    System.getProperty("ncnn.dump")?.let { systemProperty("ncnn.dump", it) }
}

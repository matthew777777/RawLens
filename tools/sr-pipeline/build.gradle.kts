plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("com.rawlens.srpipeline.cli.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

dependencies {
    implementation("com.github.wendykierp:JTransforms:3.1")
    testImplementation("junit:junit:4.13.2")
}

// Native proof properties (SrpBridgeTest): pass
// -PsrpLib=<libsrpipeline.dylib> -PsrpSpv=<merge_accumulate.spv> to execute
// the real JNI entries on the desktop Vulkan loader. Unset (CI default):
// the GPU tests skip and the suite stays green without Vulkan.
tasks.withType<Test> {
    systemProperty("srpipeline.lib", (findProperty("srpLib") as String?) ?: "")
    systemProperty("srpipeline.spv", (findProperty("srpSpv") as String?) ?: "")
    systemProperty("srpipeline.spv_gat", (findProperty("srpSpvGat") as String?) ?: "")
    systemProperty("srpipeline.spv_align", (findProperty("srpSpvAlign") as String?) ?: "")
    systemProperty("srpipeline.spv_corr", (findProperty("srpSpvCorr") as String?) ?: "")
    systemProperty("srpipeline.spv_fft", (findProperty("srpSpvFft") as String?) ?: "")
    systemProperty("srpipeline.spv_ica", (findProperty("srpSpvIca") as String?) ?: "")
    systemProperty("srpipeline.spv_demosaic", (findProperty("srpSpvDemosaic") as String?) ?: "")
    systemProperty("srpipeline.spv_grad", (findProperty("srpSpvGrad") as String?) ?: "")
    systemProperty("srpipeline.spv_kernels", (findProperty("srpSpvKernels") as String?) ?: "")
    systemProperty("srpipeline.spv_divide", (findProperty("srpSpvDivide") as String?) ?: "")
    systemProperty("srpipeline.spv_robust", (findProperty("srpSpvRobust") as String?) ?: "")
    systemProperty("srpipeline.spv_merge_splat", (findProperty("srpSpvMergeSplat") as String?) ?: "")
    systemProperty("srpipeline.fixtures", (findProperty("srpFixtures") as String?) ?: "parity/fixtures")
    systemProperty("srpipeline.gpuref", (findProperty("srpGpuRef") as String?) ?: "parity/gpu_reference")
    systemProperty("srpipeline.burst", (findProperty("srpBurstDir") as String?) ?: "/Users/monikamalinowska/Downloads/Photos/Photos/IMG_20260927_134544_522")
    systemProperty("srpipeline.lut", (findProperty("srpLut") as String?) ?: "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution/data/IMG_20260927_134544_522_noise.npz")
}

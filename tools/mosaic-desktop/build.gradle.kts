import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("com.matthew.srdesktop.MosaicMain")
    // Streaming + memory-mapped accumulators; 2GB headroom is plenty.
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":tools:sr-vulkan"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Host ncnnMl (see NcnnLoader): the ported ML processors loadLibrary by
// name, so every launch path must carry the staged per-platform dir on
// java.library.path. `run`/tests use systemProperty; installDist ships the
// loose lib under lib/native/<plat> and injects the flag via JAVA_OPTS
// (applicationDefaultJvmArgs cannot expand $APP_HOME: the start script
// escapes metacharacters).
tasks.named<JavaExec>("run") {
    dependsOn(":tools:sr-vulkan:processResources")
    val nativeRoot = project(":tools:sr-vulkan").layout.buildDirectory
        .dir("resources/main/native").get().asFile
    systemProperty(
        "java.library.path",
        listOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64")
            .map { File(nativeRoot, it).absolutePath }.joinToString(":")
    )
    // Full-grid Vulkan dispatch (unset = phone watchdog budgets).
    systemProperty("srvk.sliceBudget", Int.MAX_VALUE.toString())
}

distributions {
    main {
        contents {
            from(project(":tools:sr-vulkan").layout.buildDirectory.dir("resources/main/native")) {
                include("*/libncnnMl.*")
                into("lib/native")
            }
        }
    }
}
tasks.named("installDist") { dependsOn(":tools:sr-vulkan:stageNcnn") }
tasks.named("distZip") { dependsOn(":tools:sr-vulkan:stageNcnn") }

tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        val anchor = "# Collect all arguments for the java command:"
        val text = unixScript.readText()
        require(anchor in text) {
            "start-script template changed; re-anchor the ncnn JAVA_OPTS injection"
        }
        val dollar = '$'.toString()
        val block = """
            |# Full-grid Vulkan dispatch (unset = phone watchdog budgets).
            |JAVA_OPTS="-Dsrvk.sliceBudget=2147483647 ${dollar}JAVA_OPTS"
            |# Host ncnnMl (see NcnnLoader): point java.library.path at the
            |# staged per-platform dir when the native lib shipped in the dist.
            |for _ncnn_dir in "${dollar}APP_HOME"/lib/native/*/; do
            |    if ls "${dollar}{_ncnn_dir}"libncnnMl.* >/dev/null 2>&1; then
            |        JAVA_OPTS="-Djava.library.path=${dollar}{_ncnn_dir%/} ${dollar}JAVA_OPTS"
            |        break
            |    fi
            |done
            |unset _ncnn_dir
            |
        """.trimMargin()
        unixScript.writeText(text.replace(anchor, block + anchor))
    }
}

// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Desktop pre-loader for the host `ncnnMl` library (same JNI bridge + custom
 * layers as Android, built by `src/main/cpp-ncnn`).
 *
 * Unlike [SrVulkan], this library CANNOT be extract-and-[System.load]ed: the
 * ported ML processors carry the phone's `static { loadLibrary("ncnnMl") }`
 * lines, and the JVM only dedups [System.loadLibrary] by name, so the
 * library must be findable on `java.library.path` itself. The Gradle build
 * arranges that (tests + `run` tasks via `systemProperty`, installDist via
 * a start-script `JAVA_OPTS` injection); plain `java -jar` runs need
 * `-Djava.library.path=<dir-containing-libncnnMl>`.
 *
 * Call [tryLoad] before touching the ML processors: true warms the JVM's
 * loaded-library table (the processors' static blocks then no-op), false
 * means the callers' analytic/CPU fallbacks stay in charge.
 */
object NcnnLoader {
    @Volatile private var loaded = false

    /** Load `ncnnMl` via `loadLibrary`, throwing a guided error on failure. */
    @Synchronized
    fun load() {
        if (loaded) return
        try {
            System.loadLibrary("ncnnMl")
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "ncnnMl: libncnnMl not found on java.library.path=" +
                    System.getProperty("java.library.path") +
                    " (build it with :tools:sr-vulkan:buildNcnn and run via" +
                    " installDist/run, or pass" +
                    " -Djava.library.path=<tools/sr-vulkan/build/resources/main/native/<plat>>)"
            ).also { it.initCause(e) }
        }
        loaded = true
    }

    /**
     * Best-effort [load]: returns false (after one stderr line) when the
     * host library is unavailable so callers can keep their fallbacks.
     */
    fun tryLoad(): Boolean {
        if (loaded) return true
        return try {
            load()
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("ncnnMl unavailable (${e.message}); using fallbacks")
            false
        }
    }
}

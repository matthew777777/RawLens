// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin facade for the unified native `srvulkan` library (same C++/JNI
 * sources on Android and desktop). Loading order: explicit
 * `-Dsrvulkan.lib=<path>` override, then `System.loadLibrary` (Android
 * packaging and developers with the lib on `java.library.path`), then
 * classpath extraction of the Gradle-staged per-platform binary.
 */
object SrVulkan {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.getProperty("srvulkan.lib")?.let { path ->
            System.load(path)
            loaded = true
            return
        }
        runCatching {
            System.loadLibrary("srvulkan")
            loaded = true
            return
        }
        val (dir, file) = platformResource()
            ?: throw UnsatisfiedLinkError(
                "srvulkan: unsupported platform ${System.getProperty("os.name")}" +
                    "/${System.getProperty("os.arch")} (need Linux/macOS)"
            )
        val resource = "/native/$dir/$file"
        val stream = SrVulkan::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "srvulkan: $resource missing from the classpath " +
                    "(build the native lib: :tools:sr-vulkan:buildNative)"
            )
        val tmp = File.createTempFile("libsrvulkan-", "-$file").apply { deleteOnExit() }
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.setReadable(true, true)
        tmp.setExecutable(true, true)
        System.load(tmp.absolutePath)
        loaded = true
    }

    /** Pair of (resource dir, file name) for this OS/arch, or null. */
    private fun platformResource(): Pair<String, String>? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val dir = when {
            "mac" in os && ("aarch64" in arch || "arm64" in arch) -> "macos-arm64"
            "mac" in os && "x86_64" in arch -> "macos-x64"
            "linux" in os && "aarch64" in arch -> "linux-arm64"
            "linux" in os && ("amd64" in arch || "x86_64" in arch) -> "linux-x64"
            else -> return null
        }
        val ext = if ("mac" in os) "dylib" else "so"
        return dir to "libsrvulkan.$ext"
    }

    /** Open a compute context; exactly one owner must [Handle.close] it. */
    fun open(): Handle {
        load()
        return Handle(nativeInit())
    }

    class Handle internal constructor(private var handle: Long) : Closeable {
        fun deviceInfo(): String {
            check(handle != 0L) { "srvulkan handle is closed" }
            return nativeDeviceInfo(handle)
        }

        fun deviceCount(): Int {
            check(handle != 0L) { "srvulkan handle is closed" }
            return nativeDeviceCount(handle)
        }

        fun deviceName(index: Int): String {
            check(handle != 0L) { "srvulkan handle is closed" }
            return nativeDeviceName(handle, index)
        }

        /** Create a shader module from SPIR-V bytes; returns its handle. */
        fun loadModule(spirv: ByteArray): Long {
            check(handle != 0L) { "srvulkan handle is closed" }
            require(spirv.size % 4 == 0) { "SPIR-V size must be a multiple of 4" }
            val direct = ByteBuffer.allocateDirect(spirv.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(spirv); flip() }
            return nativeLoadModule(handle, direct)
        }

        fun destroyModule(module: Long) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeDestroyModule(handle, module)
        }

        fun createImage(w: Int, h: Int, format: Int): Long {
            check(handle != 0L) { "srvulkan handle is closed" }
            return nativeCreateImage(handle, w, h, format)
        }

        fun writeImage(image: Long, bytes: ByteBuffer) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeWriteImage(handle, image, bytes)
        }

        fun readImage(image: Long, out: ByteBuffer) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeReadImage(handle, image, out)
        }

        fun readImageRegion(image: Long, x: Int, y: Int, w: Int, h: Int, out: ByteBuffer) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeReadImageRegion(handle, image, x, y, w, h, out)
        }

        fun destroyImage(image: Long) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeDestroyImage(handle, image)
        }

        fun createPipeline(
            module: Long,
            uboBinding: Int,
            uboSize: Int,
            imgBindings: IntArray,
            imgKinds: IntArray
        ): Long {
            check(handle != 0L) { "srvulkan handle is closed" }
            return nativeCreatePipeline(handle, module, uboBinding, uboSize, imgBindings, imgKinds)
        }

        fun bindImage(pipeline: Long, binding: Int, image: Long) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeBindImage(handle, pipeline, binding, image)
        }

        fun writeUniforms(pipeline: Long, bytes: ByteBuffer) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeWriteUniforms(handle, pipeline, bytes)
        }

        fun dispatch(pipeline: Long, gx: Int, gy: Int, gz: Int) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeDispatch(handle, pipeline, gx, gy, gz)
        }

        fun destroyPipeline(pipeline: Long) {
            check(handle != 0L) { "srvulkan handle is closed" }
            nativeDestroyPipeline(handle, pipeline)
        }

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeShutdown(h)
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeDeviceInfo(handle: Long): String
    private external fun nativeDeviceCount(handle: Long): Int
    private external fun nativeDeviceName(handle: Long, index: Int): String
    private external fun nativeLoadModule(handle: Long, words: ByteBuffer): Long
    private external fun nativeDestroyModule(handle: Long, module: Long)
    private external fun nativeCreateImage(handle: Long, w: Int, h: Int, format: Int): Long
    private external fun nativeWriteImage(handle: Long, image: Long, bytes: ByteBuffer)
    private external fun nativeReadImage(handle: Long, image: Long, out: ByteBuffer)
    private external fun nativeReadImageRegion(
        handle: Long, image: Long, x: Int, y: Int, w: Int, h: Int, out: ByteBuffer
    )
    private external fun nativeDestroyImage(handle: Long, image: Long)
    private external fun nativeCreatePipeline(
        handle: Long, module: Long, uboBinding: Int, uboSize: Int,
        imgBindings: IntArray, imgKinds: IntArray
    ): Long
    private external fun nativeBindImage(handle: Long, pipeline: Long, binding: Int, image: Long)
    private external fun nativeWriteUniforms(handle: Long, pipeline: Long, bytes: ByteBuffer)
    private external fun nativeDispatch(handle: Long, pipeline: Long, gx: Int, gy: Int, gz: Int)
    private external fun nativeDestroyPipeline(handle: Long, pipeline: Long)
    private external fun nativeShutdown(handle: Long)
}

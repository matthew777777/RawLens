// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.io.OutputStream

/**
 * Kotlin facade for the pinned TinyDNG writer (`rawLensDng` native library,
 * header-first streaming mode): the header, IFD, extras and sub-IFDs go out
 * at [open] with precomputed offsets, then quantized little-endian strips
 * append in index order, and [close] verifies completeness. Works with any
 * [OutputStream] (files, MediaStore, in-memory) — the sink never seeks.
 *
 * Loading order: explicit `-Drawlens.dnglib=<path>` override (unit tests),
 * then `System.loadLibrary` (Android packaging, developers with the lib on
 * `java.library.path`), then classpath extraction of the Gradle-staged
 * per-platform binary (desktop).
 */
object TinyDngImageWriter {
    const val TAG_EXIF_IFD_POINTER = 34665
    const val TAG_GPS_IFD_POINTER = 34853

    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.getProperty("rawlens.dnglib")?.let { path ->
            System.load(path)
            loaded = true
            return
        }
        runCatching {
            System.loadLibrary("rawLensDng")
            loaded = true
            return
        }
        val (dir, file) = platformResource()
            ?: throw UnsatisfiedLinkError(
                "rawLensDng: unsupported platform ${System.getProperty("os.name")}" +
                    "/${System.getProperty("os.arch")} (need Linux/macOS/Android)"
            )
        val resource = "/native/$dir/$file"
        val stream = TinyDngImageWriter::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "rawLensDng: $resource missing from the classpath " +
                    "(build the native lib: :tools:sr-vulkan:buildDngNative)"
            )
        val tmp = File.createTempFile("librawLensDng-", "-$file").apply { deleteOnExit() }
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
        return dir to "librawLensDng.$ext"
    }

    /**
     * Opens a header-first write: emits the TIFF header, IFD, extras and
     * sub-IFDs to [output] immediately and returns the native handle.
     * [fields] must not duplicate tinydng-generated tags (geometry, strips,
     * photometric, compression); sub-IFD lists may be null/empty to omit.
     */
    fun open(
        output: OutputStream,
        width: Int,
        height: Int,
        samplesPerPixel: Int,
        bitsPerSample: Int,
        sampleFormat: Int,
        photometric: Int,
        rowsPerStrip: Int,
        fields: TiffFields,
        exif: TiffFields?,
        gps: TiffFields?
    ): Long {
        load()
        return nativeOpen(width, height, samplesPerPixel, bitsPerSample,
            sampleFormat, photometric, rowsPerStrip,
            fields.descriptors.toIntArray(), fields.payloads.toTypedArray(),
            exif?.takeUnless { it.isEmpty }?.descriptors?.toIntArray(),
            exif?.takeUnless { it.isEmpty }?.payloads?.toTypedArray(),
            gps?.takeUnless { it.isEmpty }?.descriptors?.toIntArray(),
            gps?.takeUnless { it.isEmpty }?.payloads?.toTypedArray(),
            output)
    }

    /** Appends one full strip (`width*rows*samples*bits/8` bytes, LE). */
    fun append(handle: Long, stripIndex: Int, strip: ByteArray) {
        nativeAppend(handle, stripIndex, strip)
    }

    /** Verifies every strip arrived and releases the native handle. */
    fun close(handle: Long) {
        nativeClose(handle)
    }

    /**
     * One-shot write for callers holding all pixels: opens with
     * [rowsPerStrip], appends each band via [fillStrip], closes. [fillStrip]
     * must synchronously fill exactly one strip's bytes for [stripIndex].
     */
    fun write(
        output: OutputStream,
        width: Int,
        height: Int,
        samplesPerPixel: Int,
        bitsPerSample: Int,
        sampleFormat: Int,
        photometric: Int,
        rowsPerStrip: Int,
        fields: TiffFields,
        exif: TiffFields?,
        gps: TiffFields?,
        stripCount: Int,
        fillStrip: (stripIndex: Int) -> ByteArray
    ) {
        val handle = open(output, width, height, samplesPerPixel, bitsPerSample,
            sampleFormat, photometric, rowsPerStrip, fields, exif, gps)
        // A failing fill (e.g. non-finite samples rejected by the caller's
        // quantizer) must surface its own exception: the close of an
        // incomplete image would otherwise mask it with an IOException.
        // The handle is still released; its error is then irrelevant.
        var complete = false
        try {
            for (strip in 0 until stripCount) append(handle, strip, fillStrip(strip))
            complete = true
        } finally {
            if (complete) close(handle) else runCatching { close(handle) }
        }
    }

    private external fun nativeOpen(
        width: Int, height: Int, samplesPerPixel: Int, bitsPerSample: Int,
        sampleFormat: Int, photometric: Int, rowsPerStrip: Int,
        descriptors: IntArray, payloads: Array<ByteArray>,
        exifDescriptors: IntArray?, exifPayloads: Array<ByteArray>?,
        gpsDescriptors: IntArray?, gpsPayloads: Array<ByteArray>?,
        output: OutputStream
    ): Long

    private external fun nativeAppend(handle: Long, stripIndex: Int, strip: ByteArray)
    private external fun nativeClose(handle: Long)
}

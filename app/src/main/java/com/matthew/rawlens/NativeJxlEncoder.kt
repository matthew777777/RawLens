// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * libjxl-backed JPEG XL encoder for developed output (up to 16-bit integer
 * plus 16/32-bit float, mirroring darktable's export options).
 *
 * The native library is optional: it is only built when CMake
 * `RAWLENS_ENABLE_JXL` is on. [isAvailable] probes for it without throwing,
 * and [encode] raises a plain [IOException] naming the missing library when
 * it is absent, so the app never crashes on builds without the encoder.
 */
object NativeJxlEncoder {
    private const val LIBRARY = "jxlEncoder"

    @Volatile
    private var availability: Boolean? = null

    /** True once the native JXL encoder library has loaded successfully. */
    @Synchronized
    fun isAvailable(): Boolean {
        availability?.let { return it }
        val loaded = runCatching { System.loadLibrary(LIBRARY) }.isSuccess
        availability = loaded
        return loaded
    }

    /**
     * Encodes 3-channel [rgb] (packed by [JxlPixelPack]) straight to [descriptor].
     *
     * @param rgb direct buffer of width*height RGB samples: UINT16, FLOAT16,
     * or FLOAT32 according to [settings]; native byte order, top-down rows.
     * @param orientation EXIF orientation of the stored pixels (1..8); written
     * to the container header so it agrees with the `Exif`-box tag. Viewers
     * that honor only the container signal show the frame sideways otherwise.
     *
     * HDR transfer and mastering peak come from [settings]: PQ selects the
     * ST2084 transfer function and the measured content peak
     * ([JxlPixelPack.peakNits], darktable's MaxCLL); black frames keep
     * libjxl's default peak.
     */
    @Throws(IOException::class)
    fun encode(
        rgb: ByteBuffer,
        width: Int,
        height: Int,
        descriptor: ParcelFileDescriptor,
        settings: JxlOutputSettings,
        iccProfile: ByteArray = ByteArray(0),
        /** Raw TIFF blob; the native side frames it as an `Exif` box (darktable parity). */
        exif: ByteArray = ByteArray(0),
        /** UTF-8 XMP packet, stored as an `xml ` box when non-empty. */
        xmp: ByteArray = ByteArray(0),
        orientation: Int = 1
    ) {
        val resolved = settings.resolved()
        require(rgb.isDirect) { "JXL encoder input must be a direct buffer" }
        require(orientation in 1..8) { "JXL orientation must be 1..8" }
        if (!isAvailable()) {
            throw IOException(
                "JPEG XL encoder is not bundled in this build (offline or " +
                    "source-only build; a networked build bundles libjxl automatically)"
            )
        }
        val hdrPq = resolved.hdrTransfer == JxlHdrTransfer.PQ
        val error = nativeEncode(
            rgb,
            width,
            height,
            resolved.bitDepth.bits,
            JxlPixelPack.exponentBits(resolved),
            JxlPixelPack.dataType(resolved),
            resolved.quality,
            resolved.effort,
            resolved.decodingSpeed,
            if (resolved.displayP3) 1 else 0,
            iccProfile,
            exif,
            xmp,
            descriptor.fd,
            orientation,
            if (hdrPq) TRANSFER_PQ else TRANSFER_SRGB,
            if (hdrPq) JxlPixelPack.peakNits(rgb, resolved).takeIf { it > 0f } ?: 0f else 0f
        )
        if (error != null) throw IOException(error)
    }

    private external fun nativeEncode(
        rgb: ByteBuffer,
        width: Int,
        height: Int,
        bitsPerSample: Int,
        exponentBits: Int,
        dataType: Int,
        quality: Int,
        effort: Int,
        decodingSpeed: Int,
        displayP3: Int,
        iccProfile: ByteArray,
        exif: ByteArray,
        xmp: ByteArray,
        fd: Int,
        orientation: Int,
        transferFunction: Int,
        intensityTarget: Float
    ): String?

    /** Mirrors the native transfer-function switch (sRGB default, ST2084 PQ). */
    private const val TRANSFER_SRGB = 0
    private const val TRANSFER_PQ = 1
}

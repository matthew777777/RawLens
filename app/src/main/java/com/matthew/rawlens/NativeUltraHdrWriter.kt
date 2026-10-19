// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * RAWR-parity Ultra HDR file writer. Dual-encodes the SDR base and the
 * GLES-generated wide-range gain map with libjpeg-turbo, runs the adaptive
 * map fit (p99.9 ceiling + shoulder + chroma knee), and assembles the JPEG_R
 * container (MPF + GContainer XMP + hdrgm + ISO 21496-1) natively — replacing
 * the platform Bitmap.compress JPEG/R path and its fixed luma map.
 */
object NativeUltraHdrWriter {
    init { System.loadLibrary("dngCreator") }

    @Throws(IOException::class)
    fun encode(
        base: Bitmap,
        gainmap: Bitmap,
        descriptor: ParcelFileDescriptor,
        settings: JpegOutputSettings,
    ) {
        val resolved = settings.resolvedForPlatform()
        val icc = JpegIccProfileProvider.profile(resolved.displayP3)
        val error = nativeEncodeUltraHdr(
            base,
            gainmap,
            descriptor.fd,
            resolved.jpegQuality,
            resolved.chromaSubsampling.nativeValue,
            icc,
            resolved.gainmapQuality,
            resolved.gainMapMinLog2,
            resolved.gainMapMaxLog2,
            resolved.gainmapGamma,
            resolved.gainmapOffsetSdr,
            resolved.gainmapOffsetHdr,
            // Capacities describe the display ladder, not the fitted ceiling.
            0f,
            resolved.gainMapMaxLog2,
            resolved.multiChannelMap,
            resolved.gainMapFitMargin,
            resolved.gainMapFitStep,
            resolved.gainMapFitFloor,
            resolved.gainMapDisplayCap,
            resolved.shoulderWidth,
            resolved.shoulderKneeBelowMax,
            resolved.baseShare,
            resolved.baseExponent,
            resolved.chromaKnee,
        )
        if (error != null) throw IOException(error)
    }

    @Suppress("LongParameterList")
    private external fun nativeEncodeUltraHdr(
        baseBitmap: Bitmap,
        mapBitmap: Bitmap,
        fd: Int,
        baseQuality: Int,
        baseSubsampling: Int,
        iccProfile: ByteArray,
        mapQuality: Int,
        minLog2: Float,
        maxLog2: Float,
        gamma: Float,
        offsetSdr: Float,
        offsetHdr: Float,
        capacityMin: Float,
        capacityMax: Float,
        multiChannel: Boolean,
        fitMargin: Float,
        fitStep: Float,
        fitFloor: Float,
        displayCap: Float,
        shoulderWidth: Float,
        shoulderKneeBelowMax: Float,
        baseShare: Float,
        baseExponent: Float,
        chromaKnee: Float,
    ): String?
}

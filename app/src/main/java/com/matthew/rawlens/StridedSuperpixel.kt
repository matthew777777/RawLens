// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.media.Image

/**
 * Mean-superpixel RGB image: one pixel per [stride]x[stride] sensor block.
 *
 * Each pixel is the block mean of normalized codes — mean R, mean green over
 * both G1/G2 sites (identical to the mean of per-quad (G1+G2)/2), mean B —
 * the same demosaic-by-average the RAW viewfinder tonemap applies per quad
 * (`vec3(b.r, (b.g + b.b) * 0.5, b.a)`), generalized to a stride window so no
 * sensor site is skipped. Stride 2 degenerates exactly to the VF superpixel.
 */
internal data class StridedSuperpixelImage(
    val width: Int,
    val height: Int,
    /** Row-major RGB triples, (code - black) / (white - black) block means. */
    val rgb: FloatArray,
    /** Sensor-pixel stride the image was sampled with. */
    val stride: Int
)

/**
 * Strided mean-superpixel downsampler for Bayer RAW.
 *
 * Point-sampling one quad per output pixel (the viewfinder's live `step`)
 * skips (step-2) pixels between quads; this averages every site in each
 * stride block instead, so a 4080x3060 frame at stride 4 yields a full-data
 * 1020x765 RGB image with no aliasing from skipped sites. Pure Kotlin +
 * java.nio (no GLES): usable from meters, thumbnails, and JVM unit tests.
 * Not a per-frame viewfinder path — the VF keeps its NEON/Vulkan tiers.
 */
internal object StridedSuperpixel {
    /**
     * Output dims for a [cropWidth]x[cropHeight] crop at [stride]: floor
     * division per axis, so a trailing partial block is dropped (even crops
     * at even strides divide exactly, e.g. 4080x3060 / 4 = 1020x765).
     */
    fun outputDims(cropWidth: Int, cropHeight: Int, stride: Int): Pair<Int, Int> {
        require(stride >= 2 && stride % 2 == 0) { "stride must be even and >= 2" }
        require(cropWidth >= stride && cropHeight >= stride) { "crop smaller than stride" }
        return (cropWidth / stride) to (cropHeight / stride)
    }

    /**
     * Samples [image]'s first plane into mean-superpixel RGB. [blackLevels]
     * are Camera2 order (TL, TR, BL, BR); [whiteLevel] shared. Coordinates
     * are full-sensor pixels via [sensorOriginX]/[sensorOriginY] for CFA
     * parity. The buffer position is never disturbed; samples are read in
     * the buffer's own byte order (native order on device). Returns null
     * for unsupported geometry instead of throwing.
     */
    @Suppress("LongParameterList")
    fun sample(
        image: Image,
        width: Int,
        height: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        pattern: BayerPattern?,
        sensorOriginX: Int,
        sensorOriginY: Int,
        blackLevels: List<Float>?,
        whiteLevel: Float?,
        stride: Int
    ): StridedSuperpixelImage? {
        if (pattern == null || blackLevels == null || blackLevels.size != 4 || whiteLevel == null) return null
        if (!whiteLevel.isFinite() || blackLevels.any { !it.isFinite() }) return null
        if (blackLevels.any { whiteLevel <= it }) return null
        if (stride < 2 || stride % 2 != 0) return null
        if (cropWidth < stride || cropHeight < stride) return null
        if (cropWidth % 2 != 0 || cropHeight % 2 != 0) return null
        if (cropLeft < 0 || cropTop < 0 || width < 2 || height < 2) return null
        if (pixelStride < 2 || rowStride < (cropWidth - 1) * pixelStride + 2) return null
        val plane = image.planes?.firstOrNull() ?: return null
        val (outW, outH) = outputDims(cropWidth, cropHeight, stride)
        // duplicate() resets byte order to big-endian; restore the source
        // buffer's order or every 16-bit sample misreads (same convention
        // as RawSensorUnpacker, which sets order explicitly after duplicating).
        val input = plane.buffer.duplicate()
        input.order(plane.buffer.order())
        val dataOrigin = input.position()
        val limit = input.limit().toLong()
        val rgb = FloatArray(outW * outH * 3)
        for (oy in 0 until outH) for (ox in 0 until outW) {
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var rCount = 0
            var gCount = 0
            var bCount = 0
            for (iy in 0 until stride) for (ix in 0 until stride) {
                val planeX = cropLeft + ox * stride + ix
                val planeY = cropTop + oy * stride + iy
                val sensorX = sensorOriginX + planeX
                val sensorY = sensorOriginY + planeY
                val offset = dataOrigin +
                    planeY.toLong() * rowStride +
                    planeX.toLong() * pixelStride
                if (offset + 2 > limit) return null
                val code = input.getShort(offset.toInt()).toInt() and 0xffff
                val black = blackLevels[((sensorY and 1) shl 1) or (sensorX and 1)]
                val n = (code - black).coerceAtLeast(0f) / (whiteLevel - black)
                when (pattern.colorAt(sensorX, sensorY)) {
                    CfaColor.RED -> { rSum += n; rCount++ }
                    CfaColor.GREEN -> { gSum += n; gCount++ }
                    CfaColor.BLUE -> { bSum += n; bCount++ }
                }
            }
            if (rCount == 0 || gCount == 0 || bCount == 0) return null
            val base = (oy * outW + ox) * 3
            rgb[base] = rSum / rCount
            rgb[base + 1] = gSum / gCount
            rgb[base + 2] = bSum / bCount
        }
        return StridedSuperpixelImage(outW, outH, rgb, stride)
    }

    /**
     * Downsamples an unpacked normalized CFA to the same mean-superpixel
     * grid. Test seam and future CPU path; identical block semantics to
     * [sample] (values are already normalized, so no levels needed).
     */
    fun fromCfa(cfa: UnpackedRawCfa, stride: Int): StridedSuperpixelImage? {
        if (stride < 2 || stride % 2 != 0) return null
        if (cfa.width < stride || cfa.height < stride) return null
        if (cfa.width % 2 != 0 || cfa.height % 2 != 0) return null
        if (cfa.values.size != cfa.width * cfa.height) return null
        val (outW, outH) = outputDims(cfa.width, cfa.height, stride)
        val rgb = FloatArray(outW * outH * 3)
        for (oy in 0 until outH) for (ox in 0 until outW) {
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var rCount = 0
            var gCount = 0
            var bCount = 0
            for (iy in 0 until stride) for (ix in 0 until stride) {
                val x = ox * stride + ix
                val y = oy * stride + iy
                val sensorX = cfa.sensorCropLeft + x
                val sensorY = cfa.sensorCropTop + y
                when (cfa.pattern.colorAt(sensorX, sensorY)) {
                    CfaColor.RED -> { rSum += cfa.values[y * cfa.width + x]; rCount++ }
                    CfaColor.GREEN -> { gSum += cfa.values[y * cfa.width + x]; gCount++ }
                    CfaColor.BLUE -> { bSum += cfa.values[y * cfa.width + x]; bCount++ }
                }
            }
            if (rCount == 0 || gCount == 0 || bCount == 0) return null
            val base = (oy * outW + ox) * 3
            rgb[base] = rSum / rCount
            rgb[base + 1] = gSum / gCount
            rgb[base + 2] = bSum / bCount
        }
        return StridedSuperpixelImage(outW, outH, rgb, stride)
    }
}

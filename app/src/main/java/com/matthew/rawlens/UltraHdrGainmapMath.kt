// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * CPU mirror of the GLES Ultra HDR gain-map encode
 * (agx_srgb8.glsl / final_display.glsl gainmap block, RAWR gainmap.comp
 * semantics without the clip-mask dazzle and glow taps, which stay disabled
 * in this pipeline: clipBoost 0, glowStrength 0).
 *
 * Pure Kotlin with no Android dependencies so JVM unit tests can pin the
 * encode contract: identity floor, highlight headroom, hue preservation,
 * saturation protection, and the white-tap luma fallback. The shaders must
 * match this function texel-for-texel (up to their ±0.5 LSB dither, which
 * this mirror skips).
 */
object UltraHdrGainmapMath {
    /** Encodes one gain-map texel from display-linear HDR/SDR taps. Returns RGB codes 0..255. */
    fun encodeTexel(
        hdrLin: FloatArray,
        sdrLin: FloatArray,
        settings: JpegOutputSettings = JpegOutputSettings(),
    ): IntArray {
        val resolved = settings.resolvedForPlatform()
        val offS = max(resolved.gainmapOffsetSdr, 1e-6f)
        val offH = max(resolved.gainmapOffsetHdr, 1e-6f)
        val exposure = max(resolved.hdrExposure, 1e-6f)
        val hdr = FloatArray(3) { max(hdrLin[it] * exposure, 0f) }
        val sdr = FloatArray(3) { max(sdrLin[it], 0f) }

        val sdrMax = max(sdr[0], max(sdr[1], sdr[2]))
        val sdrMin = min(sdr[0], min(sdr[1], sdr[2]))
        val sdrSat = (sdrMax - sdrMin) / max(sdrMax, 1e-3f)
        val satW = resolved.satProtect * smoothstep(0.5f, 0.9f, sdrMax) * sdrSat.coerceIn(0f, 1f)

        val logGain3 = FloatArray(3) {
            log2(max((hdr[it] + offH) / (sdr[it] + offS), 1e-9f)).toFloat()
        }
        val lumaHdr = dot709(hdr)
        val lumaSdr = dot709(sdr)
        val logGainLuma = log2(max((lumaHdr + offH) / (lumaSdr + offS), 1e-9f)).toFloat()
        val toneScale = smoothstep(0.03f, 0.50f, lumaSdr) * (1f - satW)

        val hdrMax = max(hdr[0], max(hdr[1], hdr[2]))
        val hdrMin = min(hdr[0], min(hdr[1], hdr[2]))
        val hdrSat = (hdrMax - hdrMin) / max(hdrMax, 1e-3f)
        val hdrWhiteW = (1f - smoothstep(0.05f, 0.15f, hdrSat)) * smoothstep(0.5f, 0.9f, lumaHdr)
        val mcOn = if (resolved.multiChannelMap) 1f else 0f
        val useLuma = max(1f - mcOn, hdrWhiteW * mcOn)

        val range = max(resolved.gainMapMaxLog2 - resolved.gainMapMinLog2, 1e-6f)
        return IntArray(3) { c ->
            val logFinal = logGain3[c] * toneScale + (logGainLuma * toneScale - logGain3[c] * toneScale) * useLuma
            val norm = ((logFinal - resolved.gainMapMinLog2) / range).coerceIn(0f, 1f)
            val recovery = norm.toDouble().pow(resolved.gainmapGamma.toDouble()).toFloat()
            (floor(recovery * 255f + 0.5f).toInt()).coerceIn(0, 255)
        }
    }

    private fun dot709(c: FloatArray): Float = c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Decodes one channel back to log2 gain (metadata round-trip for tests). */
    fun decodeLog2(code: Int, settings: JpegOutputSettings = JpegOutputSettings()): Float {
        val resolved = settings.resolvedForPlatform()
        return resolved.gainMapMinLog2 +
            (code.coerceIn(0, 255) / 255f) * (resolved.gainMapMaxLog2 - resolved.gainMapMinLog2)
    }
}

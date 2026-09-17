// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.media.Image
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Bounded green-proxy thumbnail for reference metrics.
 *
 * L = 0.5 * (G1 + G2) in black-subtracted, white-normalized dimensionless
 * samples. Grid steps are chosen so neither dimension exceeds [maxQuads]
 * quads; every quad is read through the pattern-derived plane map, so no
 * shader or RGGB assumption leaks in. Small enough for unit tests and later
 * CPU/GPU parity fixtures (see [thumbnailHash]).
 */
internal data class GreenThumbnail(
    val quadsW: Int,
    val quadsH: Int,
    val luminance: FloatArray,
    /** Grid step in quads used to bound the working set. */
    val stepQuads: Int,
    /** Stable hex digest of the quantized grid for parity fixtures. */
    val thumbnailHash: String
)

/** Per-frame normalized image features, each in [0, 1]. */
internal data class ReferenceImageFeatures(
    /** Fraction of green samples below highlight and above the floor. */
    val unclippedFraction: Float,
    /** Noise-normalized green-gradient percentile. */
    val sharpnessPercentile: Float,
    /** Fraction of samples inside the useful [0.05, 0.95] range. */
    val exposureUtility: Float
)

/**
 * Structured debug record for one reconstruction-reference candidate.
 * Every candidate gets exactly one; exactly one record has [selected].
 */
internal data class ReferenceCandidateDebug(
    val index: Int,
    val timestampNanos: Long,
    val unclippedFraction: Float,
    val sharpnessPercentile: Float,
    val inverseNoise: Float,
    val poseCentrality: Float,
    val exposureUtility: Float,
    val gyroBlur: Float,
    val focusAePenalty: Float,
    val total: Float,
    val selected: Boolean,
    /** Provenance notes, e.g. noise=iso-fallback, gyro=missing. */
    val notes: List<String>
)

/** All inputs for scoring one candidate; nullable metadata degrades gracefully. */
internal data class ReferenceCandidateInput(
    val index: Int,
    val timestampNanos: Long,
    val thumbnail: GreenThumbnail?,
    val exposureNanos: Long?,
    val sensitivityIso: Int?,
    /** Flattened Camera2 SENSOR_NOISE_PROFILE S,O pairs, or null. */
    val noiseProfile: DoubleArray?,
    val blackLevel: Float,
    val whiteLevel: Float,
    val gyroSamples: List<GyroSample>,
    val rollingShutterSkewNanos: Long,
    val afState: Int?,
    val aeState: Int?,
    val lensState: Int?
)

/** Outcome of deterministic reference selection in burst order. */
internal data class ReferenceSelection(
    val selectedIndex: Int,
    val autoIndex: Int,
    val records: List<ReferenceCandidateDebug>
)

/**
 * Deterministic reconstruction-reference selection. ZSL candidate ranking
 * only forms the candidate set; the reference is whoever maximizes the
 * plan formula below, so the first frame is never privileged. Near-ties
 * resolve to the temporally central candidate to minimize displacement.
 * Pure Kotlin + java.nio: usable without GLES. No merge wiring.
 */
internal object BurstReferenceSelection {
    const val W_UNCLIPPED = 1.8f
    const val W_SHARPNESS = 1.5f
    const val W_NOISE = 1.2f
    const val W_CENTRALITY = 0.8f
    const val W_EXPOSURE = 0.4f
    const val W_GYRO = -1.8f
    const val W_FOCUS_AE = -1.0f

    /** Tie band in score units; tied set resolves to the temporal median. */
    const val TIE_BAND = 0.05f

    /** Maximum thumbnail grid dimension in quads. */
    const val MAX_THUMB_QUADS = 192

    /** Normalized white fraction at/above which samples count as clipped. */
    const val CLIP_REJECT = 0.995f

    /**
     * Scores one candidate:
     * 1.8*unclipped + 1.5*sharp + 1.2*noise + 0.8*centrality + 0.4*exposure
     * - 1.8*gyroBlur - 1.0*focusAe. Dimensionless score units.
     */
    fun scoreTotal(
        unclippedFraction: Float,
        sharpnessPercentile: Float,
        inverseNoise: Float,
        poseCentrality: Float,
        exposureUtility: Float,
        gyroBlur: Float,
        focusAePenalty: Float
    ): Float = W_UNCLIPPED * unclippedFraction +
        W_SHARPNESS * sharpnessPercentile +
        W_NOISE * inverseNoise +
        W_CENTRALITY * poseCentrality +
        W_EXPOSURE * exposureUtility +
        W_GYRO * gyroBlur +
        W_FOCUS_AE * focusAePenalty

    /**
     * Selects the reference over burst-order [candidates] (at least one).
     * Missing optional metadata degrades each component conservatively and
     * records provenance in [ReferenceCandidateDebug.notes]; it never throws.
     */
    fun select(candidates: List<ReferenceCandidateInput>): ReferenceSelection {
        require(candidates.isNotEmpty()) { "need at least one reference candidate" }
        val times = candidates.map { it.timestampNanos }.sorted()
        val maxIso = candidates.map { it.sensitivityIso ?: 100 }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val scored = candidates.map { candidate ->
            var notes = emptyList<String>()
            fun note(text: String) {
                notes = notes + text
            }
            val image = measureThumbnail(candidate, ::note)
            val noise = inverseNoise(candidate, maxIso, ::note)
            val gyro = gyroBlur(candidate, ::note)
            val centrality = poseCentrality(candidate.timestampNanos, times)
            val focus = focusAePenalty(candidate, ::note)
            val total = scoreTotal(
                image.unclippedFraction, image.sharpnessPercentile, noise,
                centrality, image.exposureUtility, gyro, focus
            )
            ScoredCandidate(candidate, image, noise, centrality, gyro, focus, total, notes)
        }
        val best = scored.maxOf { it.total }
        val tied = scored.filter { best - it.total <= TIE_BAND }.map { it.candidate.index }.sorted()
        val auto = if (tied.size == 1) tied[0] else tied[tied.size / 2]
        val selected = candidates.firstOrNull { it.index == auto }?.index ?: scored.first().candidate.index
        return ReferenceSelection(
            selectedIndex = selected,
            autoIndex = auto,
            records = scored.map {
                ReferenceCandidateDebug(
                    index = it.candidate.index,
                    timestampNanos = it.candidate.timestampNanos,
                    unclippedFraction = it.image.unclippedFraction,
                    sharpnessPercentile = it.image.sharpnessPercentile,
                    inverseNoise = it.noise,
                    poseCentrality = it.centrality,
                    exposureUtility = it.image.exposureUtility,
                    gyroBlur = it.gyro,
                    focusAePenalty = it.focus,
                    total = it.total,
                    selected = it.candidate.index == selected,
                    notes = it.notes
                )
            }
        )
    }

    private data class ScoredCandidate(
        val candidate: ReferenceCandidateInput,
        val image: ReferenceImageFeatures,
        val noise: Float,
        val centrality: Float,
        val gyro: Float,
        val focus: Float,
        val total: Float,
        val notes: List<String>
    )

    private fun measureThumbnail(
        candidate: ReferenceCandidateInput,
        note: (String) -> Unit
    ): ReferenceImageFeatures {
        val thumb = candidate.thumbnail
        if (thumb == null || thumb.luminance.isEmpty()) {
            note("thumbnail=missing")
            return ReferenceImageFeatures(0.9f, 0.5f, 0.5f)
        }
        val lum = thumb.luminance
        var unclipped = 0
        var useful = 0
        for (v in lum) {
            if (v < CLIP_REJECT && v > 1e-4f) unclipped++
            if (v in 0.05f..0.95f) useful++
        }
        // Noise-normalized gradient sharpness: 90th percentile of gradient
        // magnitude over predicted midtone sigma, saturating to [0, 1].
        val sigma = midtoneSigma(candidate).coerceAtLeast(1e-4f)
        val mags = FloatArray(lum.size)
        val w = thumb.quadsW
        val h = thumb.quadsH
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = 0.5f * (lum[y * w + x + 1] - lum[y * w + x - 1])
            val gy = 0.5f * (lum[(y + 1) * w + x] - lum[(y - 1) * w + x])
            mags[y * w + x] = sqrt(gx * gx + gy * gy) / sigma
        }
        mags.sort()
        val p90 = mags[(mags.size * 0.9).toInt().coerceIn(0, mags.size - 1)]
        return ReferenceImageFeatures(
            unclippedFraction = unclipped.toFloat() / lum.size,
            sharpnessPercentile = (p90 / (p90 + 6f)).coerceIn(0f, 1f),
            exposureUtility = useful.toFloat() / lum.size
        )
    }

    /**
     * Predicted green noise sigma at midtones (dimensionless), from the HAL
     * profile when valid, else from a conservative ISO mapping. Never throws.
     */
    internal fun midtoneSigma(candidate: ReferenceCandidateInput): Float {
        val profile = candidate.noiseProfile
        val denom = (candidate.whiteLevel - candidate.blackLevel).toDouble()
        if (profile != null && profile.size == 6 && profile.all(Double::isFinite) &&
            denom > 0 && profile[2] > 0
        ) {
            // Green pair (S, O): var(code) = S*code + O, transformed through
            // black/white normalization at signal y = 0.18.
            val code = 0.18 * denom + candidate.blackLevel
            val variance = (profile[2] * code + profile[3]) / (denom * denom)
            if (variance.isFinite() && variance > 0) return sqrt(variance).toFloat()
        }
        val iso = (candidate.sensitivityIso ?: 100).coerceAtLeast(1)
        return sqrt(0.02 * iso / 100.0).toFloat().coerceAtLeast(1e-3f)
    }

    private fun inverseNoise(
        candidate: ReferenceCandidateInput,
        maxIso: Int,
        note: (String) -> Unit
    ): Float {
        val profile = candidate.noiseProfile
        if (profile == null || profile.size != 6 || profile.any { !it.isFinite() }) {
            note("noise=iso-fallback")
        }
        val sigma = midtoneSigma(candidate).toDouble().coerceAtLeast(1e-6)
        val snr = 0.18 / sigma
        val snrPart = (snr / (snr + 8.0)).toFloat().coerceIn(0f, 1f)
        val iso = (candidate.sensitivityIso ?: 100).coerceAtLeast(1)
        val isoPart = (1f - (iso.toFloat() / maxIso) * 0.3f).coerceIn(0f, 1f)
        return (isoPart * 0.5f + snrPart * 0.5f).coerceIn(0f, 1f)
    }

    private fun gyroBlur(candidate: ReferenceCandidateInput, note: (String) -> Unit): Float {
        if (candidate.gyroSamples.isEmpty()) {
            note("gyro=missing")
            return 0f
        }
        var travel = 0.0
        var prev: GyroSample? = null
        for (sample in candidate.gyroSamples) {
            if (prev != null) {
                val dt = (sample.timestampNanos - prev.timestampNanos).coerceAtLeast(0L) / 1e9
                val magnitude = sqrt(
                    sample.xRadiansPerSecond * sample.xRadiansPerSecond +
                        sample.yRadiansPerSecond * sample.yRadiansPerSecond +
                        sample.zRadiansPerSecond * sample.zRadiansPerSecond
                )
                travel += magnitude * dt
            }
            prev = sample
        }
        // 20 mrad of travel during exposure is already heavily blurred.
        return (1.0 - exp(-travel / 0.02)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Inverse median timestamp distance to the other candidates, in [0, 1].
     * Image-refined centrality arrives with alignment; timestamps are the
     * Prompt-3 approximation.
     */
    internal fun poseCentrality(timestampNanos: Long, sortedTimes: List<Long>): Float {
        if (sortedTimes.size < 2) return 1f
        val distances = sortedTimes.map { abs(it - timestampNanos).toDouble() }.sorted()
        val median = distances[sortedTimes.size / 2]
        val span = (sortedTimes.last() - sortedTimes.first()).toDouble().coerceAtLeast(1.0)
        return (1.0 - median / span).toFloat().coerceIn(0f, 1f)
    }

    private fun focusAePenalty(candidate: ReferenceCandidateInput, note: (String) -> Unit): Float {
        if (candidate.afState == null && candidate.aeState == null && candidate.lensState == null) {
            note("focus=unknown")
            return 0.5f
        }
        val ae = when (candidate.aeState) {
            CaptureResult.CONTROL_AE_STATE_SEARCHING,
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> 1f
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> 0.5f
            else -> 0f
        }
        val af = when (candidate.afState) {
            5, 6 -> 1f // NOT_FOCUSED_LOCKED, PASSIVE_UNFOCUSED
            1, 3 -> 0.5f // PASSIVE_SCAN, ACTIVE_SCAN
            else -> 0f
        }
        val lens = if (candidate.lensState == 1) 1f else 0f // LENS_STATE_MOVING
        return maxOf(ae, af, lens)
    }
}

/**
 * Bounded stride sampler for the reference green proxy. Reads full Bayer
 * quads straight from the RAW plane buffer (honoring row/pixel stride and
 * crop), so working memory stays tiny on full-resolution frames. Returns
 * null for unsupported geometry instead of throwing.
 */
internal object GreenThumbnailSampler {
    /**
     * Samples [image]'s first plane on a quad grid capped at [maxQuads].
     * [blackLevels] are Camera2 order (TL, TR, BL, BR); [whiteLevel] shared.
     * Coordinates are full-sensor pixels via [sensorOriginX]/[sensorOriginY]
     * for CFA parity. The buffer position is never disturbed; samples are
     * read in the buffer's own byte order (native order on device).
     */
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
        maxQuads: Int = BurstReferenceSelection.MAX_THUMB_QUADS
    ): GreenThumbnail? {
        if (pattern == null || blackLevels == null || blackLevels.size != 4 || whiteLevel == null) return null
        if (!whiteLevel.isFinite() || blackLevels.any { !it.isFinite() }) return null
        if (blackLevels.any { whiteLevel <= it }) return null
        if (cropWidth < 8 || cropHeight < 8 || cropWidth % 2 != 0 || cropHeight % 2 != 0) return null
        if (pixelStride < 2 || rowStride < (cropWidth - 1) * pixelStride + 2) return null
        val plane = image.planes?.firstOrNull() ?: return null
        val quadsW = cropWidth / 2
        val quadsH = cropHeight / 2
        val step = ((maxOf(quadsW, quadsH) + maxQuads - 1) / maxQuads).coerceAtLeast(1)
        val outW = (quadsW + step - 1) / step
        val outH = (quadsH + step - 1) / step
        val out = FloatArray(outW * outH)
        // duplicate() resets byte order to big-endian; restore the source
        // buffer's order or every 16-bit sample misreads (same convention
        // as RawSensorUnpacker, which sets order explicitly after duplicating).
        val input = plane.buffer.duplicate()
        input.order(plane.buffer.order())
        val dataOrigin = input.position()
        val limit = input.limit().toLong()
        var n = 0
        for (qy in 0 until outH) for (qx in 0 until outW) {
            val quadX = qx * step
            val quadY = qy * step
            var greenSum = 0f
            var greens = 0
            for (iy in 0..1) for (ix in 0..1) {
                val planeX = cropLeft + quadX * 2 + ix
                val planeY = cropTop + quadY * 2 + iy
                val sensorX = sensorOriginX + planeX
                val sensorY = sensorOriginY + planeY
                if (pattern.colorAt(sensorX, sensorY) != CfaColor.GREEN) continue
                val offset = dataOrigin +
                    planeY.toLong() * rowStride +
                    planeX.toLong() * pixelStride
                if (offset + 2 > limit) return null
                val code = input.getShort(offset.toInt()).toInt() and 0xffff
                val black = blackLevels[((sensorY and 1) shl 1) or (sensorX and 1)]
                greenSum += (code - black).coerceAtLeast(0f) / (whiteLevel - black)
                greens++
            }
            if (greens == 0) return null
            out[n++] = greenSum / greens
        }
        return GreenThumbnail(outW, outH, out, step, hashGrid(out))
    }

    /**
     * Downsamples an unpacked normalized CFA to the same bounded green grid.
     * Test seam and future CPU path; identical plane semantics to [sample].
     */
    fun fromCfa(
        cfa: UnpackedRawCfa,
        maxQuads: Int = BurstReferenceSelection.MAX_THUMB_QUADS
    ): GreenThumbnail? {
        if (cfa.width < 8 || cfa.height < 8) return null
        val quadsW = cfa.width / 2
        val quadsH = cfa.height / 2
        val step = ((maxOf(quadsW, quadsH) + maxQuads - 1) / maxQuads).coerceAtLeast(1)
        val outW = (quadsW + step - 1) / step
        val outH = (quadsH + step - 1) / step
        val out = FloatArray(outW * outH)
        var n = 0
        for (qy in 0 until outH) for (qx in 0 until outW) {
            val quadX = qx * step
            val quadY = qy * step
            var greenSum = 0f
            var greens = 0
            for (iy in 0..1) for (ix in 0..1) {
                val x = quadX * 2 + ix
                val y = quadY * 2 + iy
                val sensorX = cfa.sensorCropLeft + x
                val sensorY = cfa.sensorCropTop + y
                if (cfa.pattern.colorAt(sensorX, sensorY) != CfaColor.GREEN) continue
                greenSum += cfa.values[y * cfa.width + x]
                greens++
            }
            if (greens == 0) return null
            out[n++] = greenSum / greens
        }
        return GreenThumbnail(outW, outH, out, step, hashGrid(out))
    }

    private fun hashGrid(values: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val scratch = ByteArray(4)
        for (v in values) {
            val bits = (v.coerceIn(0f, 4f) * 4096f).toInt()
            scratch[0] = (bits shr 24).toByte()
            scratch[1] = (bits shr 16).toByte()
            scratch[2] = (bits shr 8).toByte()
            scratch[3] = bits.toByte()
            digest.update(scratch)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

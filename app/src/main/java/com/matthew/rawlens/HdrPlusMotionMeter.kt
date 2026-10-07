// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * HDR+ motion metering (GCam `BuildPayloadBurstSpec`-style instrumentation).
 *
 * Per consecutive frame pair, measures a normalized frame-difference score
 * over strided samples of the packed uint16 buffers, plus the inter-frame
 * gap from capture timestamps. Gyro ego-motion travels alongside (already
 * recorded per ZSL frame by [CameraMotionTracker]; 0 when the tracker is
 * off). Pure JVM math — no Android dependencies — so the whole unit is
 * testable on the host.
 *
 * Step 2 of the auto plan: instrumentation only. Nothing here changes
 * merge behavior; step 3 consumes [MotionSummary] for auto strength,
 * auto path, and base-frame selection.
 */
object HdrPlusMotionMeter {
    /** Sample every Nth pixel per pair (~48k samples at 12 MP). */
    const val SAMPLE_STRIDE = 16
    /** Local-motion blocks are this many grid samples on a side. */
    const val BLOCK_SAMPLES = 8
    /**
     * Strength-map blocks are this many grid samples on a side (32px
     * at stride 16 — must satisfy
     * MAP_BLOCK_SAMPLES * SAMPLE_STRIDE == HdrPlusAutoTuning.MAP_BLOCK_PX).
     */
    const val MAP_BLOCK_SAMPLES = 2

    /** Per-frame brightness + texture facts for base-frame scoring. */
    data class FrameStats(val meanLevel: Double, val sharpness: Double)

    /**
     * Burst-level summary. [pairDeltas] and [pairDtMillis] hold N-1
     * entries (NaN where unmeasurable); aggregates skip NaN and are NaN
     * when nothing was measurable. [pairHotFraction] holds the local
     * motion score per pair; [frameMeanLevel]/[frameSharpness] hold one
     * entry per frame. [pairMismatchRatios] holds one row-major
     * ceil-cover ratio grid per pair (empty when unmeasured).
     */
    data class MotionSummary(
        val pairDeltas: List<Double>,
        val pairDtMillis: List<Double>,
        val pairGyro: List<Double>,
        val meanDelta: Double,
        val maxDelta: Double,
        val meanDtMillis: Double,
        val meanGyro: Double,
        val maxGyro: Double,
        val pairHotFraction: List<Double>,
        val frameMeanLevel: List<Double>,
        val frameSharpness: List<Double>,
        val maxHotFraction: Double,
        val pairMismatchRatios: List<DoubleArray> = emptyList(),
        val mapCellsX: Int = 0,
        val mapCellsY: Int = 0
    ) {
        /** GCam-style one-line report for the merge log. */
        fun logLine(frames: Int): String {
            fun fmt(value: Double, digits: Int): String =
                if (value.isFinite()) "%.${digits}f".format(java.util.Locale.US, value) else "?"
            return "HDR+ motion N=$frames dtMean=${fmt(meanDtMillis, 1)}ms " +
                "deltaMean=${fmt(meanDelta, 4)} deltaMax=${fmt(maxDelta, 4)} " +
                "gyroMean=${fmt(meanGyro, 3)} gyroMax=${fmt(maxGyro, 3)} rad/s"
        }
    }

    /**
     * Normalized mean absolute difference between two tight-packed uint16
     * frames (native order, [pixelCount] samples each). Samples every
     * [stride]-th pixel starting at 0; [normalizationRange] is the DN
     * span of one normalized unit (white minus black mean). Returns NaN
     * when the range is not positive or no sample is readable.
     */
    fun meanAbsDiffNormalized(
        prev: ByteBuffer,
        curr: ByteBuffer,
        pixelCount: Int,
        stride: Int = SAMPLE_STRIDE,
        normalizationRange: Double
    ): Double {
        if (!normalizationRange.isFinite() || normalizationRange <= 0.0) return Double.NaN
        if (pixelCount <= 0 || stride <= 0) return Double.NaN
        // duplicate() does NOT preserve byte order (resets to big-endian),
        // so restate native order explicitly — the packed buffers are LE.
        val a = prev.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val b = curr.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        if (a.remaining() < pixelCount || b.remaining() < pixelCount) return Double.NaN
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < pixelCount) {
            val va = a[i].toInt() and 0xFFFF
            val vb = b[i].toInt() and 0xFFFF
            sum += abs(va - vb) / normalizationRange
            n++
            i += stride
        }
        return if (n == 0) Double.NaN else sum / n
    }

    /**
     * Per-frame brightness + texture over the strided sample grid.
     * Sharpness is the mean absolute horizontal neighbor difference
     * between adjacent grid samples (same row); both values are
     * normalized by [normalizationRange]. Either field is NaN when
     * unmeasurable.
     */
    fun frameStats(
        packed: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int = SAMPLE_STRIDE,
        normalizationRange: Double
    ): FrameStats {
        if (!normalizationRange.isFinite() || normalizationRange <= 0.0 ||
            width <= 0 || height <= 0 || stride <= 0
        ) {
            return FrameStats(Double.NaN, Double.NaN)
        }
        val buf = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        if (buf.remaining() < width * height) return FrameStats(Double.NaN, Double.NaN)
        fun code(x: Int, y: Int): Int = buf[y * width + x].toInt() and 0xFFFF
        var levelSum = 0.0
        var levelCount = 0
        var sharpSum = 0.0
        var sharpCount = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val v = code(x, y)
                levelSum += v / normalizationRange
                levelCount++
                if (x + stride < width) {
                    sharpSum += abs(v - code(x + stride, y)) / normalizationRange
                    sharpCount++
                }
                x += stride
            }
            y += stride
        }
        return FrameStats(
            meanLevel = if (levelCount == 0) Double.NaN else levelSum / levelCount,
            sharpness = if (sharpCount == 0) Double.NaN else sharpSum / sharpCount
        )
    }

    /**
     * Local-motion score: fraction of full [BLOCK_SAMPLES]-sided grid
     * blocks whose mean absolute pair difference exceeds
     * [hotRatio] x the block's own texture (the mean of both frames'
     * horizontal neighbor differences). Global MAD cannot tell a dark
     * street scene from a bright still one; this ratio can (measured
     * 1.2% on moving street vs 0.0% on still landscape at ratio 2.0).
     * Returns 0 when no full block exists, NaN when the range is bad.
     */
    fun blockHotFraction(
        prev: ByteBuffer,
        curr: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int = SAMPLE_STRIDE,
        normalizationRange: Double,
        hotRatio: Double = 2.0
    ): Double {
        if (!normalizationRange.isFinite() || normalizationRange <= 0.0 ||
            width <= 0 || height <= 0 || stride <= 0 || !hotRatio.isFinite()
        ) {
            return Double.NaN
        }
        val a = prev.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val b = curr.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        if (a.remaining() < width * height || b.remaining() < width * height) {
            return Double.NaN
        }
        val gridW = (width + stride - 1) / stride
        val gridH = (height + stride - 1) / stride
        val blocksX = gridW / BLOCK_SAMPLES
        val blocksY = gridH / BLOCK_SAMPLES
        if (blocksX <= 0 || blocksY <= 0) return 0.0
        fun code(buf: java.nio.ShortBuffer, gx: Int, gy: Int): Int =
            buf[(gy * stride) * width + gx * stride].toInt() and 0xFFFF
        var hot = 0
        var total = 0
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                var madSum = 0.0
                var sharpSum = 0.0
                for (sy in 0 until BLOCK_SAMPLES) {
                    val gy = by * BLOCK_SAMPLES + sy
                    for (sx in 0 until BLOCK_SAMPLES) {
                        val gx = bx * BLOCK_SAMPLES + sx
                        val va = code(a, gx, gy)
                        val vb = code(b, gx, gy)
                        madSum += abs(va - vb) / normalizationRange
                        if (sx + 1 < BLOCK_SAMPLES) {
                            sharpSum += abs(va - code(a, gx + 1, gy)) / normalizationRange
                            sharpSum += abs(vb - code(b, gx + 1, gy)) / normalizationRange
                        }
                    }
                }
                val samples = BLOCK_SAMPLES * BLOCK_SAMPLES
                // Sharpness averages both frames' neighbor diffs to match
                // the fitted metric.
                val sharp = sharpSum / (2 * BLOCK_SAMPLES * (BLOCK_SAMPLES - 1))
                if (madSum / samples > hotRatio * sharp) hot++
                total++
            }
        }
        return hot.toDouble() / total
    }

    /**
     * Per-block mismatch ratios over the strided sample grid: block MAD
     * over the block's own texture (mean of both frames' intra-block
     * horizontal neighbor differences — the same texture definition as
     * [blockHotFraction]). Row-major over the ceil-cover block grid
     * (partial edge blocks use their available samples and skew strict
     * when texture is unmeasurable — a safe direction over a 16px edge
     * strip). Null when the range or geometry is bad. Feeds the
     * strength-map builder (step-4 maps).
     */
    /**
     * Strength-map grid covering W×H (blocksX x blocksY). Single source
     * for the grid [blockMismatchRatios] emits; the host/builder contract
     * is (W+31)/32 x (H+31)/32 at stride 16 / 2-sample blocks.
     */
    fun mapCells(
        width: Int,
        height: Int,
        stride: Int = SAMPLE_STRIDE,
        blockSamples: Int = MAP_BLOCK_SAMPLES
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || stride <= 0 || blockSamples <= 0) return 0 to 0
        val gridW = (width + stride - 1) / stride
        val gridH = (height + stride - 1) / stride
        return ((gridW + blockSamples - 1) / blockSamples) to
            ((gridH + blockSamples - 1) / blockSamples)
    }

    fun blockMismatchRatios(
        prev: ByteBuffer,
        curr: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int = SAMPLE_STRIDE,
        blockSamples: Int = MAP_BLOCK_SAMPLES,
        normalizationRange: Double
    ): DoubleArray? {
        if (!normalizationRange.isFinite() || normalizationRange <= 0.0 ||
            width <= 0 || height <= 0 || stride <= 0 || blockSamples <= 0
        ) {
            return null
        }
        val a = prev.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val b = curr.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        if (a.remaining() < width * height || b.remaining() < width * height) return null
        val (blocksX, blocksY) = mapCells(width, height, stride, blockSamples)
        if (blocksX <= 0 || blocksY <= 0) return null
        val gridW = (width + stride - 1) / stride
        val gridH = (height + stride - 1) / stride
        fun code(buf: java.nio.ShortBuffer, gx: Int, gy: Int): Int =
            buf[(gy * stride) * width + gx * stride].toInt() and 0xFFFF
        return DoubleArray(blocksX * blocksY) { bi ->
            val bx = bi % blocksX
            val by = bi / blocksX
            var madSum = 0.0
            var texSum = 0.0
            var samples = 0
            var pairs = 0
            for (sy in 0 until blockSamples) {
                val gy = by * blockSamples + sy
                if (gy >= gridH) continue
                for (sx in 0 until blockSamples) {
                    val gx = bx * blockSamples + sx
                    if (gx >= gridW) continue
                    val va = code(a, gx, gy)
                    val vb = code(b, gx, gy)
                    madSum += abs(va - vb) / normalizationRange
                    samples++
                    if (sx + 1 < blockSamples && gx + 1 < gridW) {
                        texSum += abs(va - code(a, gx + 1, gy)) / normalizationRange
                        texSum += abs(vb - code(b, gx + 1, gy)) / normalizationRange
                        pairs++
                    }
                }
            }
            val mad = if (samples == 0) 0.0 else madSum / samples
            val tex = if (pairs == 0) 0.0 else texSum / (2 * pairs)
            mad / (tex + 1e-9)
        }
    }

    /**
     * Assembles a [MotionSummary] from per-pair measurements. Gyro entries
     * use NaN for unreported frames; timestamp gaps use NaN when either
     * endpoint is unknown (<= 0). Frame facts default to unknown.
     */
    fun summarize(
        pairDeltas: List<Double>,
        prevTimestampsNs: List<Long>,
        currTimestampsNs: List<Long>,
        pairGyroRadS: List<Double>,
        pairHotFraction: List<Double> = List(pairDeltas.size) { Double.NaN },
        frameMeanLevel: List<Double> = emptyList(),
        frameSharpness: List<Double> = emptyList(),
        pairMismatchRatios: List<DoubleArray> = emptyList(),
        mapCellsX: Int = 0,
        mapCellsY: Int = 0
    ): MotionSummary {
        require(pairDeltas.size == prevTimestampsNs.size &&
            pairDeltas.size == currTimestampsNs.size &&
            pairDeltas.size == pairGyroRadS.size &&
            pairDeltas.size == pairHotFraction.size) {
            "Motion inputs must cover the same pairs"
        }
        require(frameMeanLevel.isEmpty() || frameMeanLevel.size == pairDeltas.size + 1) {
            "Frame means must cover every frame"
        }
        require(frameSharpness.isEmpty() || frameSharpness.size == pairDeltas.size + 1) {
            "Frame sharpness must cover every frame"
        }
        require(pairMismatchRatios.isEmpty() || pairMismatchRatios.size == pairDeltas.size) {
            "Mismatch ratio grids must cover every pair"
        }
        val dts = pairDeltas.indices.map { i ->
            val prev = prevTimestampsNs[i]
            val curr = currTimestampsNs[i]
            if (prev > 0 && curr > 0) (curr - prev) / 1e6 else Double.NaN
        }
        fun List<Double>.finiteMean(): Double {
            val finite = filter { it.isFinite() }
            return if (finite.isEmpty()) Double.NaN else finite.average()
        }
        fun List<Double>.finiteMax(): Double {
            val finite = filter { it.isFinite() }
            return if (finite.isEmpty()) Double.NaN else finite.max()
        }
        return MotionSummary(
            pairDeltas = pairDeltas.toList(),
            pairDtMillis = dts,
            pairGyro = pairGyroRadS.toList(),
            meanDelta = pairDeltas.finiteMean(),
            maxDelta = pairDeltas.finiteMax(),
            meanDtMillis = dts.finiteMean(),
            meanGyro = pairGyroRadS.finiteMean(),
            maxGyro = pairGyroRadS.finiteMax(),
            pairHotFraction = pairHotFraction.toList(),
            frameMeanLevel = frameMeanLevel.toList(),
            frameSharpness = frameSharpness.toList(),
            maxHotFraction = pairHotFraction.finiteMax(),
            pairMismatchRatios = pairMismatchRatios.toList(),
            mapCellsX = mapCellsX,
            mapCellsY = mapCellsY
        )
    }
}

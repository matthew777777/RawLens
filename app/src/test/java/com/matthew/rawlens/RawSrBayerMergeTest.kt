// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Prompt 4D CPU oracle tests. Criteria mirror docs/raw-sr-merge.md §12; the
 * single kernel width below is a fixed centralized constant, never tuned per
 * scene. The merge consumes precomputed covariance fields (reference Alg. 4:
 * interpolate covariances, invert per pixel) and no tuning object.
 */
class RawSrBayerMergeTest {
    private companion object {
        const val W = 32
        const val H = 24
        const val QW = W / 2
        const val QH = H / 2
        /** Centralized kernel width in quad pixels, shared by every test. */
        const val K = 1.0
    }

    private fun isoCovariance(qw: Int = QW, qh: Int = QH, k: Double = K): RawSrKernelCovariance.MatrixField {
        // Isotropic covariance diag(k^2): the merge inverts per pixel, so the
        // effective precision is diag(1/k^2) over raw-unit distances.
        val p = (k * k).toFloat()
        val values = FloatArray(qw * qh * 4)
        for (i in 0 until qw * qh) {
            values[i * 4] = p
            values[i * 4 + 1] = 0f
            values[i * 4 + 2] = 0f
            values[i * 4 + 3] = p
        }
        return RawSrKernelCovariance.MatrixField(qw, qh, values)
    }

    private fun field(
        w: Int = W, h: Int = H, tileSize: Int = 8,
        flowAt: (tx: Int, ty: Int) -> Pair<Float, Float> = { _, _ -> 0f to 0f },
        reliable: Boolean = true
    ): RawSrAlignmentField {
        // Raw-lattice field (Jamy-L convention): raw-pixel coverage with
        // raw-unit vectors. Fixtures size the frame as a tile multiple.
        val columns = w / tileSize
        val rows = h / tileSize
        val tiles = List(columns * rows) { i ->
            val (dx, dy) = flowAt(i % columns, i / columns)
            RawSrTileFlow(0f, 0f, dx, dy, 0f, reliable)
        }
        return RawSrAlignmentField(w, h, tileSize, columns, rows, tiles)
    }

    private fun robust(
        qw: Int = QW, qh: Int = QH, value: (x: Int, y: Int) -> Float = { _, _ -> 1f }
    ): RawSrRobustness.FrameRobustness {
        return RawSrRobustness.FrameRobustness(
            qw, qh, FloatArray(qw * qh) { i -> value(i % qw, i / qw) }, IntArray(qw * qh))
    }

    private fun sceneFrame(
        w: Int = W, h: Int = H,
        pattern: BayerPattern = BayerPattern.RGGB,
        ox: Int = 0, oy: Int = 0,
        flow: RawSrAlignmentField? = null,
        robustness: RawSrRobustness.FrameRobustness? = null,
        k: Double = K,
        chroma: RawSrBayerMerge.ChromaParams? = null,
        scene: (sx: Int, sy: Int, color: CfaColor) -> Float
    ): RawSrBayerMerge.MergeFrame {
        val samples = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            scene(ox + x, oy + y, pattern.colorAt(ox + x, oy + y))
        }
        return RawSrBayerMerge.MergeFrame(
            w, h, samples, pattern.shifted(ox, oy), ox, oy,
            isoCovariance(w / 2, h / 2, k),
            flow ?: field(w, h),
            robustness ?: robust(w / 2, h / 2),
            chroma = chroma)
    }

    private fun constScene(r: Float, g: Float, b: Float): (Int, Int, CfaColor) -> Float =
        { _, _, color -> when (color) { CfaColor.RED -> r; CfaColor.GREEN -> g; CfaColor.BLUE -> b } }

    private fun refOnlyOf(frame: RawSrBayerMerge.MergeFrame): RawSrBayerMerge.MergeResult =
        RawSrBayerMerge.merge(frame, emptyList(), referenceOnly = true)

    private fun channelMean(result: RawSrBayerMerge.MergeResult, c: Int, border: Int = 2): Double {
        var sum = 0.0
        var n = 0
        for (y in border until result.height - border) for (x in border until result.width - border) {
            sum += result.rgb[(y * result.width + x) * 3 + c]
            n++
        }
        return sum / n
    }

    private fun maxAbsDiff(
        a: RawSrBayerMerge.MergeResult, b: RawSrBayerMerge.MergeResult, border: Int = 0
    ): Double {
        var max = 0.0
        for (y in border until a.height - border) for (x in border until a.width - border)
            for (c in 0..2) {
                val d = abs(a.rgb[(y * a.width + x) * 3 + c] - b.rgb[(y * b.width + x) * 3 + c]).toDouble()
                if (d > max) max = d
            }
        return max
    }

    @Test fun referenceOnlyPreservesConstantColourAllPatternsAndOrigins() {
        for (pattern in BayerPattern.entries) {
            for ((ox, oy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                val frame = sceneFrame(pattern = pattern, ox = ox, oy = oy, scene = constScene(0.2f, 0.5f, 0.7f))
                val out = refOnlyOf(frame)
                assertTrue(out.fallback.none { it })
                for (y in 0 until H) for (x in 0 until W) for (c in 0..2) {
                    val expected = floatArrayOf(0.2f, 0.5f, 0.7f)[c]
                    assertEquals("$pattern ($ox,$oy) ($x,$y,$c)", expected, out.rgb[(y * W + x) * 3 + c], 1e-5f)
                }
            }
        }
    }

    @Test fun lowRobustnessBlendsUniformScenes() {
        // One moving frame at r = 0.1: the reference defines no support
        // overwrite, so every pixel takes the plain kernel blend
        // ((0.4 + 0.6*0.1)/1.1 ≈ 0.41818) per channel, and no fallback trips.
        val ref = sceneFrame(scene = constScene(0.4f, 0.4f, 0.4f))
        val mov = sceneFrame(
            scene = constScene(0.6f, 0.6f, 0.6f),
            robustness = robust(value = { _, _ -> 0.1f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.none { it })
        for (c in 0..2) assertEquals(0.4181818, channelMean(out, c), 1e-6)
    }

    @Test fun robustnessIsContinuousAcrossHalf() {
        // No MIN_SUPPORT decision boundary exists in the reference path: rc
        // exactly 0.5 and rc one float-ulp below both take the plain kernel
        // blend with a clean mask, and the two outputs differ only by the
        // tiny robustness delta (no mask flip, no branch switch).
        val ref = sceneFrame(scene = constScene(0.4f, 0.4f, 0.4f))
        val atHalf = sceneFrame(
            scene = constScene(0.6f, 0.6f, 0.6f),
            robustness = robust(value = { _, _ -> 0.5f }))
        val kept = RawSrBayerMerge.merge(ref, listOf(atHalf))
        assertTrue(kept.rc.values.all { it == 0.5f })
        assertTrue(kept.fallback.none { it })
        // 0.49999997f is floatToIntBits(0.5f) - 1: the float adjacent below.
        val belowHalf = sceneFrame(
            scene = constScene(0.6f, 0.6f, 0.6f),
            robustness = robust(value = { _, _ -> 0.49999997f }))
        val also = RawSrBayerMerge.merge(ref, listOf(belowHalf))
        assertTrue(also.fallback.none { it })
        assertTrue(maxAbsDiff(kept, also) < 1e-5)
    }

    @Test fun zeroRobustnessEqualsReferenceOnly() {
        // Fully rejected moving frames (r = 0) accumulate exact zeros
        // (reference Alg. 4: `w*r` weights vanish; the skip runs before the
        // flow lookup, so accumulators are untouched). Bit-exact vs the
        // reference-only run, with a clean mask — no fallback branch exists.
        val step: (Int, Int, CfaColor) -> Float =
            { x, _, _ -> if (x < W / 2) 0.15f else 0.75f }
        val ref = sceneFrame(scene = step)
        val mov = sceneFrame(
            scene = constScene(0.9f, 0.9f, 0.9f),
            robustness = robust(value = { _, _ -> 0f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.none { it })
        assertArrayEquals(refOnlyOf(ref).rgb, out.rgb, 0f)
    }

    @Test fun acceptedEdgeKernelFringeStaysBounded() {
        // Structural residual pin (merge contract §9): on ACCEPTED edge
        // pixels the Bayer-domain scatter kernel mixes phases within its 3x3
        // window, so a one-pixel fringe remains — measured 0.227 on a 0.6
        // step with identical frames and isotropic precision (the worst
        // case: no subpixel diversity to symmetrise the per-channel tap
        // sets, no edge steering). The bound documents the residual kernel
        // fringe on accepted pixels.
        val step: (Int, Int, CfaColor) -> Float =
            { x, _, _ -> if (x < W / 2) 0.15f else 0.75f }
        val ref = sceneFrame(scene = step)
        val mov = sceneFrame(scene = step)
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        var worst = 0.0
        for (y in 0 until H) for (x in 0 until W) {
            if (out.fallback[y * W + x]) continue
            val o = (y * W + x) * 3
            val r = out.rgb[o].toDouble()
            val g = out.rgb[o + 1].toDouble()
            val b = out.rgb[o + 2].toDouble()
            worst = maxOf(worst, abs(r - g), abs(b - g))
        }
        assertTrue("kernel fringe worst=$worst", worst < 0.25)
    }

    @Test fun clippedLevelsReproducePerColourAllPhases() {
        // Reference Alg. 4 defines no highlight neutralization: per-colour
        // levels merge as-is, with or without moving-frame support. The
        // phase/origin sweep keeps pinning crop-relative routing (green at
        // full white must not leak into red/blue channels).
        for (pattern in BayerPattern.entries) for (ox in 0..1) for (oy in 0..1) {
            val ref = sceneFrame(pattern = pattern, ox = ox, oy = oy,
                scene = constScene(0.74f, 1f, 0.30f))
            for (support in listOf(0f, 1f)) {
                val moving = ref.copy(robustness = robust(value = { _, _ -> support }))
                val result = RawSrBayerMerge.merge(ref, listOf(moving))
                val levels = floatArrayOf(0.74f, 1f, 0.30f)
                for (p in 0 until W * H) for (c in 0..2) {
                    assertEquals("$pattern origin=$ox,$oy pixel=$p ch=$c", levels[c],
                        result.rgb[p * 3 + c], 1e-6f)
                }
            }
        }
    }

    @Test fun hotPixelMergesThroughKernelWithoutCensor() {
        // Single hot red tap (1.0 at (12,10), even/even = RED) in a 0.3
        // field. Reference Alg. 4 defines no tap censor: the hot tap merges
        // through the kernel weights. Only red-channel windows covering the
        // tap see it (green/blue windows hold no red tap and reproduce 0.3);
        // the center window holds the hot tap alone and resolves to exactly
        // 1.0, neighbours to a strict interior blend.
        val ref = sceneFrame(scene = { x, y, _ -> if (x == 12 && y == 10) 1f else 0.3f })
        val result = RawSrBayerMerge.merge(ref, emptyList())
        for (y in 0 until H) for (x in 0 until W) for (c in 0..2) {
            val v = result.rgb[(y * W + x) * 3 + c].toDouble()
            val inFootprint = x in 11..13 && y in 9..11
            when {
                c != 0 || !inFootprint -> assertEquals("($x,$y,$c)", 0.3, v, 1e-6)
                x == 12 && y == 10 -> assertEquals("hot center", 1.0, v, 0.0)
                else -> assertTrue("hot fringe ($x,$y) v=$v", v > 0.3 && v < 1.0)
            }
        }
    }

    @Test fun nearestHelperPicksCenterTapOnCheckerboard() {
        // The dormant burst-nearest helper (Stacker rule; no longer backing
        // any merge fallback): on a checkerboard the per-channel pick is the
        // nearest same-colour tap with deterministic loop-order tie-breaks.
        // At source (4.5, 4.5) red resolves through the center tap itself
        // (0.8, unique minimum); green and blue tie four ways each and take
        // the loop-order-first tap ((4,3) -> 0.0 and (3,3) -> 0.8).
        val checker: (Int, Int, CfaColor) -> Float =
            { x, y, _ -> if ((x + y) % 2 == 0) 0.8f else 0f }
        val frame = sceneFrame(pattern = BayerPattern.RGGB, scene = checker)
        val nearNum = DoubleArray(3)
        val nearDen = DoubleArray(3)
        RawSrBayerMerge.accumulateNearest(frame, 4.5, 4.5, 1.0, 0, W, H, nearNum, nearDen)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), nearDen, 0.0)
        assertArrayEquals(doubleArrayOf(0.8f.toDouble(), 0.0, 0.8f.toDouble()), nearNum, 0.0)
    }

    @Test fun saturatedReferenceBlendsWithoutGuard() {
        // Saturated reference (1.0) with a disagreeing moving frame (0.2) at
        // r = 0.3: the reference defines no saturated-tap guard, so every
        // channel takes the plain kernel blend
        // ((1.0 + 0.2*0.3)/1.3 ≈ 0.81538) with a clean mask.
        val ref = sceneFrame(scene = constScene(1.0f, 1.0f, 1.0f))
        val mov = sceneFrame(
            scene = constScene(0.2f, 0.2f, 0.2f),
            robustness = robust(value = { _, _ -> 0.3f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.none { it })
        for (y in 2 until H - 2) for (x in 2 until W - 2) for (c in 0..2) {
            assertEquals("($x,$y,$c)", 0.8153846, out.rgb[(y * W + x) * 3 + c].toDouble(), 1e-6)
        }
    }

    @Test fun multiFrameConstantColourPreservation() {
        val scene = constScene(0.25f, 0.6f, 0.15f)
        val ref = sceneFrame(scene = scene)
        val flows = listOf(0f to 0f, 0.5f to 0f, 0.13f to -0.29f, -1.0f to 2.0f)
        val moving = flows.map { (dx, dy) ->
            sceneFrame(flow = field(flowAt = { _, _ -> dx to dy }), scene = scene)
        }
        val out = RawSrBayerMerge.merge(ref, moving)
        val base = refOnlyOf(ref)
        assertEquals(0.0, maxAbsDiff(out, base), 1e-5)
        for (c in 0..2) {
            val expected = doubleArrayOf(0.25, 0.6, 0.15)[c]
            assertEquals(expected, channelMean(out, c), 1e-6)
        }
    }

    @Test fun redBlueOrderingAndGrayNeutralityAllPatterns() {
        for (pattern in BayerPattern.entries) {
            val frame = sceneFrame(pattern = pattern, scene = constScene(0.8f, 0.5f, 0.2f))
            val out = refOnlyOf(frame)
            assertEquals("$pattern R", 0.8, channelMean(out, 0), 1e-6)
            assertEquals("$pattern G", 0.5, channelMean(out, 1), 1e-6)
            assertEquals("$pattern B", 0.2, channelMean(out, 2), 1e-6)
            val gray = sceneFrame(pattern = pattern, scene = constScene(0.42f, 0.42f, 0.42f))
            val g = refOnlyOf(gray)
            for (c in 0..2) assertEquals("$pattern gray $c", 0.42, channelMean(g, c), 1e-6)
        }
    }

    @Test fun oddOriginShiftEquivalenceOnTexture() {
        fun textured(sx: Int, sy: Int, color: CfaColor): Float {
            val h = ((sx * 73856093) xor (sy * 19349663) xor (color.ordinal * 83492791)) and 0x7fffffff
            return 0.1f + (h % 1000) / 1000f * 0.7f
        }
        val a = sceneFrame(pattern = BayerPattern.RGGB, ox = 0, oy = 0, scene = ::textured)
        val b = sceneFrame(pattern = BayerPattern.RGGB, ox = 1, oy = 0, scene = ::textured)
        val outA = refOnlyOf(a)
        val outB = refOnlyOf(b)
        // Same sensor location, same support geometry: A[x,y] == B[x-1,y] bit-exact.
        for (y in 2 until H - 2) for (x in 2 until W - 3) for (c in 0..2) {
            assertEquals("($x,$y,$c)", outA.rgb[(y * W + x) * 3 + c], outB.rgb[(y * W + x - 1) * 3 + c], 0f)
        }
    }

    @Test fun unclampedNegativesPreserved() {
        val frame = sceneFrame(scene = constScene(-0.05f, 0.0f, -0.2f))
        val out = refOnlyOf(frame)
        assertEquals(-0.05, channelMean(out, 0), 1e-6)
        assertEquals(0.0, channelMean(out, 1), 1e-6)
        assertEquals(-0.2, channelMean(out, 2), 1e-6)
        val moving = listOf(sceneFrame(flow = field(flowAt = { _, _ -> 0.25f to 0.125f }), scene = constScene(-0.05f, 0.0f, -0.2f)))
        val merged = RawSrBayerMerge.merge(frame, moving)
        assertEquals(0.0, maxAbsDiff(merged, out), 1e-5)
    }

    @Test fun grayRampColourWithinOnePercentOfReferenceOnly() {
        fun ramp(sx: Int, sy: Int, color: CfaColor): Float {
            val gray = 0.1f + 0.8f * (sx + sy).toFloat() / (W + H).toFloat()
            return gray + color.ordinal * 0.01f
        }
        val ref = sceneFrame(scene = ::ramp)
        val shifts = listOf(0f to 0f, 0.11f to 0.07f, -0.19f to 0.23f, 0.31f to -0.13f)
        val moving = shifts.map { (dx, dy) -> sceneFrame(flow = field(flowAt = { _, _ -> dx to dy }), scene = ::ramp) }
        val out = RawSrBayerMerge.merge(ref, moving)
        val base = refOnlyOf(ref)
        for (c in 0..2) {
            val mean = channelMean(out, c)
            val refMean = channelMean(base, c)
            assertEquals("channel $c means $mean vs $refMean", refMean, mean, abs(refMean) * 0.01)
        }
        var close = 0
        var total = 0
        for (y in 2 until H - 2) for (x in 2 until W - 2) for (c in 0..2) {
            total++
            if (abs(out.rgb[(y * W + x) * 3 + c] - base.rgb[(y * W + x) * 3 + c]) <= 0.01f) close++
        }
        assertTrue("close fraction=${close.toDouble() / total}", close.toDouble() / total >= 0.99)
    }

    @Test fun multiSeedNoiseReductionAtFixedKernel() {
        for (seed in listOf(7, 99, 1234)) {
            val random = Random(seed)
            fun noisyFrame(shift: Pair<Float, Float>): RawSrBayerMerge.MergeFrame {
                val (dx, dy) = shift
                val noise = FloatArray(W * H) { random.nextGaussian().toFloat() * 0.02f }
                return sceneFrame(flow = field(flowAt = { _, _ -> dx to dy })) { sx, sy, _ ->
                    0.4f + noise[sy * W + sx]
                }
            }
            // Same noise realization layout as the reference would see is not
            // needed: variance is measured against the known flat truth.
            val refNoise = FloatArray(W * H) { Random(seed + 1).nextGaussian().toFloat() * 0.02f }
            val ref = sceneFrame { sx, sy, _ -> 0.4f + refNoise[sy * W + sx] }
            val shifts = List(7) { i -> (i * 0.13f - 0.4f) to (i * 0.07f - 0.2f) }
            val moving = shifts.map { noisyFrame(it) }
            val out = RawSrBayerMerge.merge(ref, moving)
            val rcMean = out.rc.values.average()
            assertTrue("seed=$seed rcMean=$rcMean", rcMean >= 6.0)
            var mergedVar = 0.0
            var singleVar = 0.0
            var n = 0
            for (y in 3 until H - 3) for (x in 3 until W - 3) {
                // Green channel only: every quad holds green, so the field is dense.
                val m = out.rgb[(y * W + x) * 3 + 1] - 0.4
                mergedVar += m * m
                // Single-frame baseline: the noisy reference sample at a green site.
                val sx = x
                val sy = y
                if (BayerPattern.RGGB.colorAt(sx, sy) == CfaColor.GREEN) {
                    val s = ref.samples[sy * W + sx] - 0.4f
                    singleVar += s * s
                    n++
                }
            }
            mergedVar /= (H - 6) * (W - 6)
            singleVar /= n
            // Reference-correct raw-unit kernel distances (merge.py: z over
            // raw-pixel d) narrow the K = 1 kernel 2x versus the old quad-unit
            // /2.0 distances, so there is less spatial averaging on top of the
            // multi-frame mean: seed 99 measures 0.263 (0.233 before the units
            // fix). The 0.3 bound keeps margin above the worst seed while
            // still pinning strong multi-frame reduction (<< 1.0).
            assertTrue("seed=$seed ratio=${mergedVar / singleVar}", mergedVar <= 0.3 * singleVar)
        }
    }

    @Test fun chromaLatchGuardKillsEdgeTeethAndKeepsGreenBitwise() {
        // Achromatic vertical step (0.2 | 0.8) under a razor across-edge /
        // smooth along-edge kernel (sx = 0.13 raw px, the analytic A = 2
        // across radius): the reference-verbatim spelling (chromaSigmaMpy =
        // 1.0) latches each output pixel's R/B quotient onto a single tap,
        // and the R/B latch phases disagree along the edge (chroma zipper
        // teeth). The guarded default widens only the R/B kernel, so the
        // worst R/G and B/G deviations must shrink while green stays
        // bitwise-identical.
        fun aniso(qw: Int, qh: Int, sx: Double, sy: Double): RawSrKernelCovariance.MatrixField {
            val values = FloatArray(qw * qh * 4)
            for (i in 0 until qw * qh) {
                values[i * 4] = (sx * sx).toFloat()
                values[i * 4 + 1] = 0f
                values[i * 4 + 2] = 0f
                values[i * 4 + 3] = (sy * sy).toFloat()
            }
            return RawSrKernelCovariance.MatrixField(qw, qh, values)
        }
        val edge = 16
        fun edged(flowDx: Float): RawSrBayerMerge.MergeFrame {
            val frame = sceneFrame(pattern = BayerPattern.RGGB,
                flow = field(flowAt = { _, _ -> flowDx to 0f })) { sx, _, _ ->
                if (sx < edge) 0.2f else 0.8f
            }
            return frame.copy(covariance = aniso(W / 2, H / 2, 0.13, 2.0))
        }
        // Sub-pixel-shifted movers (the real burst geometry): fractional
        // shifts break the integer-grid R/B distance ties asymmetrically,
        // so the verbatim spelling hard-latches onto nearer taps — sometimes
        // the wrong side of the edge — while the guard blends.
        val ref = edged(0f)
        val moving = listOf(0.3f, -0.4f, 0.7f, -0.9f, 1.2f, -1.5f, 0.15f).map { edged(it) }
        fun run(mpy: Double): RawSrBayerMerge.MergeResult =
            RawSrBayerMerge.merge(ref, moving, chromaSigmaMpy = mpy)
        val plain = run(1.0)
        val guarded = run(RawSrBayerMerge.CHROMA_SIGMA_MPY)
        fun worstChroma(result: RawSrBayerMerge.MergeResult): Double {
            var worst = 0.0
            for (y in 2 until H - 2) for (x in edge - 2..edge + 2) {
                val o = (y * W + x) * 3
                val g = result.rgb[o + 1].toDouble()
                worst = maxOf(worst, abs(result.rgb[o].toDouble() - g), abs(result.rgb[o + 2].toDouble() - g))
            }
            return worst
        }
        val plainWorst = worstChroma(plain)
        val guardedWorst = worstChroma(guarded)
        for ((nm, res) in listOf("plain" to plain, "guarded" to guarded)) {
            val sb = StringBuilder("$nm y=12:")
            for (x in 13..19) {
                val o = (12 * W + x) * 3
                sb.append(" x$x=(%.3f,%.3f,%.3f)".format(res.rgb[o], res.rgb[o + 1], res.rgb[o + 2]))
            }
            println(sb.toString())
        }
        assertTrue("verbatim must latch teeth (worst=$plainWorst)", plainWorst > 0.1)
        // Zero-support divide (EPS=0) removed the collapse-to-0 teeth the
        // guard once erased wholesale; what remains is latch-phase
        // disagreement (R/B picking different single taps), which the wider
        // guard kernel softens but does not eliminate on this razor edge.
        // The guard must still strictly improve the worst tooth.
        assertTrue("guarded worst=$guardedWorst must beat verbatim worst=$plainWorst",
            guardedWorst < plainWorst)
        for (y in 0 until H) for (x in 0 until W) {
            val o = (y * W + x) * 3 + 1
            assertEquals("green ($x,$y) must stay bitwise", plain.rgb[o], guarded.rgb[o], 0f)
        }
    }

    private fun Random.nextGaussian(): Double {
        var u = 0.0
        var v = 0.0
        while (u == 0.0) u = nextDouble()
        v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * kotlin.math.PI * v)
    }

    @Test fun edgesCornersTexturePreserved() {
        fun step(sx: Int, sy: Int, color: CfaColor): Float = if (sx < W / 2) 0.2f else 0.8f
        fun corner(sx: Int, sy: Int, color: CfaColor): Float =
            if ((sx < W / 2) == (sy < H / 2)) 0.7f else 0.2f
        fun checker(sx: Int, sy: Int, color: CfaColor): Float =
            if ((sx / 2 + sy / 2) % 2 == 0) 0.65f else 0.25f
        for ((name, scene) in listOf("step" to ::step, "corner" to ::corner, "checker" to ::checker)) {
            val ref = sceneFrame(scene = scene)
            val moving = List(3) { sceneFrame(scene = scene) }
            val out = RawSrBayerMerge.merge(ref, moving)
            assertEquals("$name static", 0.0, maxAbsDiff(out, refOnlyOf(ref), border = 2), 1e-5)
        }
        // Slanted-subpixel static burst keeps at least 90% of the step contrast.
        val ref = sceneFrame(scene = ::step)
        val moving = listOf(0.1f to 0f, 0f to 0.1f, 0.1f to 0.1f).map { (dx, dy) ->
            sceneFrame(flow = field(flowAt = { _, _ -> dx to dy }), scene = ::step)
        }
        val out = RawSrBayerMerge.merge(ref, moving)
        val base = refOnlyOf(ref)
        fun contrast(result: RawSrBayerMerge.MergeResult): Double {
            var left = 0.0
            var right = 0.0
            var n = 0
            for (y in 4 until H - 4) for (x in 4 until W / 2 - 2) {
                left += result.rgb[(y * W + x) * 3 + 1]
                n++
            }
            var m = 0
            for (y in 4 until H - 4) for (x in W / 2 + 2 until W - 4) {
                right += result.rgb[(y * W + x) * 3 + 1]
                m++
            }
            return right / m - left / n
        }
        assertTrue("contrast ${contrast(out)} vs ${contrast(base)}", contrast(out) >= 0.9 * contrast(base))
    }

    @Test fun foregroundOcclusionStaysGhostFreeOnRejection() {
        // Occluded block (robustness 0): the moving frame is fully rejected
        // and contributes nothing, so the block interior resolves through
        // the reference kernel quotient alone — never the +0.5 ghost
        // content. Every interior output must lie within the reference
        // window's own value range: any ghost admixture would exceed its
        // maximum, since every ghost sample sits exactly +0.5 above the
        // reference texture. No fallback trips (the reference supports
        // every pixel). Nearest r fetch snaps at quad borders (the snap
        // itself is pinned by robustnessStepSnapsToShiftedQuad), so exact
        // rejection is asserted on the guaranteed interior (every pixel
        // reads r = 0: x in 15..19 → quads 6..8, y in 11..13 → quads
        // 4..5).
        fun textured(sx: Int, sy: Int, color: CfaColor): Float = 0.15f + ((sx * 31 + sy * 17) % 23) / 23f * 0.5f
        val ref = sceneFrame(scene = ::textured)
        val inBlock = { x: Int, y: Int -> x in 12..21 && y in 8..15 }
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0f else 1f }
        ) { sx, sy, color -> if (inBlock(sx, sy)) textured(sx, sy, color) + 0.5f else textured(sx, sy, color) }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val refSamples = FloatArray(W * H) { i -> textured(i % W, i / W, BayerPattern.RGGB.colorAt(i % W, i / W)) }
        for (y in 11..13) for (x in 15..19) {
            assertTrue("no fallback ($x,$y)", !out.fallback[y * W + x])
            for (c in 0..2) {
                val v = out.rgb[(y * W + x) * 3 + c].toDouble()
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                for (oy in -1..1) for (ox in -1..1) {
                    val tx = x + ox
                    val ty = y + oy
                    if (BayerPattern.RGGB.colorAt(tx, ty).ordinal == c) {
                        val s = refSamples[ty * W + tx].toDouble()
                        lo = minOf(lo, s)
                        hi = maxOf(hi, s)
                    }
                }
                assertTrue("ghost-free ($x,$y,$c) v=$v range=[$lo,$hi]",
                    v >= lo - 1e-9 && v <= hi + 1e-9)
            }
        }
    }

    @Test fun saturatedMovingFrameMergesToReferenceExactly() {
        // Saturated ghost content (5.0) behind an r = 0 block: the block
        // interior resolves to the reference exactly. Nearest r fetch
        // snaps at quad borders (pinned by
        // robustnessStepSnapsToShiftedQuad), so exactness is asserted on
        // the guaranteed interior (x in 11..21 → quads 4..9, y in 9..15
        // → quads 3..6, all inside the r = 0 block).
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(robustness = robust { x, y -> if (x in 4..11 && y in 3..8) 0f else 1f }) { _, _, _ -> 5.0f }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val base = refOnlyOf(ref)
        for (y in 9..15) for (x in 11..21) for (c in 0..2) {
            assertEquals("($x,$y,$c)", base.rgb[(y * W + x) * 3 + c], out.rgb[(y * W + x) * 3 + c], 0f)
        }
    }

    @Test fun flowTransitionBlendsAcrossBorderButMatchesUniformFarAway() {
        // Bilinear-everywhere flow lookup (flowAtSmoothInto):
        // alternating tile-columns shifting 0 vs 2 raw px (tileSize 4)
        // blend across every border (the warp is C0-continuous, so no
        // tile tears can form; mistakes are rejected per-pixel by r,
        // not by flow vetoes). Near-border pixels 3 and 4 differ from
        // BOTH uniform outcomes; pixels far from any border (1 and 30,
        // where both blend corners clamp to one tile) equal the
        // matching uniform render bitwise.
        fun ramp(sx: Int, sy: Int, color: CfaColor): Float = 0.1f + 0.6f * sx.toFloat() / W + color.ordinal * 0.05f
        val ref = sceneFrame(scene = ::ramp)
        val mixed = field(W, H, 4, flowAt = { tx, _ -> if (tx % 2 == 0) 0f to 0f else 2f to 0f })
        val uniformA = field(W, H, 4, flowAt = { _, _ -> 0f to 0f })
        val uniformB = field(W, H, 4, flowAt = { _, _ -> 2f to 0f })
        fun movingWith(flow: RawSrAlignmentField) = sceneFrame(flow = flow, scene = ::ramp)
        val outMixed = RawSrBayerMerge.merge(ref, listOf(movingWith(mixed)))
        val outA = RawSrBayerMerge.merge(ref, listOf(movingWith(uniformA)))
        val outB = RawSrBayerMerge.merge(ref, listOf(movingWith(uniformB)))
        fun rOf(out: RawSrBayerMerge.MergeResult, x: Int, y: Int) = out.rgb[(y * W + x) * 3]
        val y = H / 2
        // Pixel 1 (corners clamp to tile 0) reads tile 0 exactly:
        // identical to uniform A, bitwise.
        assertEquals(rOf(outA, 1, y), rOf(outMixed, 1, y), 0f)
        // Pixel 30 (corners clamp to tile 7, odd -> 2px) reads tile 7
        // exactly: identical to uniform B, bitwise.
        assertEquals(rOf(outB, 30, y), rOf(outMixed, 30, y), 0f)
        // Pixels 3 and 4 straddle the 0/1 border: the blend lands
        // strictly between the two uniform warps on this monotonic
        // ramp, differing from both.
        for (x in listOf(3, 4)) {
            assertTrue("mixed must differ from A at $x",
                abs(rOf(outMixed, x, y) - rOf(outA, x, y)) > 1e-4f)
            assertTrue("mixed must differ from B at $x",
                abs(rOf(outMixed, x, y) - rOf(outB, x, y)) > 1e-4f)
            val lo = minOf(rOf(outA, x, y), rOf(outB, x, y))
            val hi = maxOf(rOf(outA, x, y), rOf(outB, x, y))
            assertTrue("mixed must sit between uniforms at $x",
                rOf(outMixed, x, y) in lo..hi)
        }
        // The test is vacuous unless the uniforms genuinely differ there.
        assertTrue(abs(rOf(outA, 3, y) - rOf(outB, 3, y)) > 1e-4f)
        assertTrue(abs(rOf(outA, 4, y) - rOf(outB, 4, y)) > 1e-4f)
    }

    @Test fun robustnessStepSnapsToShiftedQuad() {
        // Robustness twin of the flow snap test: a single r step (0 below
        // quad column 8, 1 at/above) on a monotonic ramp with a uniform
        // flow. Reference `cpu_accumulate` fetches the nearest quad with
        // the one-quad shift (min(int(lr//2-0.5))): pixels over the r = 0
        // side equal the all-rejected render exactly, pixels over the
        // r = 1 side equal the all-accepted render exactly — the step
        // snaps between columns 17 (quad 7) and 18 (quad 8).
        fun ramp(sx: Int, sy: Int, color: CfaColor): Float = 0.1f + 0.6f * sx.toFloat() / W + color.ordinal * 0.05f
        val ref = sceneFrame(scene = ::ramp)
        val shift = field(flowAt = { _, _ -> 1f to 0f })
        fun movingWith(rAt: (qx: Int) -> Float) = sceneFrame(flow = shift,
            robustness = robust(value = { qx, _ -> rAt(qx) }), scene = ::ramp)
        val outMixed = RawSrBayerMerge.merge(ref, listOf(movingWith { qx -> if (qx < 8) 0f else 1f }))
        val outA = RawSrBayerMerge.merge(ref, listOf(movingWith { 0f }))
        val outB = RawSrBayerMerge.merge(ref, listOf(movingWith { 1f }))
        assertTrue(outMixed.fallback.none { it })
        var snapped = 0
        for (y in 4 until H - 4) for (c in 0..2) {
            // Column 17 reads quad 7 (r = 0): all-rejected, exactly.
            assertEquals("reject side (17,$y,$c)",
                outA.rgb[(y * W + 17) * 3 + c], outMixed.rgb[(y * W + 17) * 3 + c], 0f)
            // Column 18 reads quad 8 (r = 1): all-accepted, exactly.
            assertEquals("accept side (18,$y,$c)",
                outB.rgb[(y * W + 18) * 3 + c], outMixed.rgb[(y * W + 18) * 3 + c], 0f)
            // Vacuous unless the endpoints genuinely differ.
            assertTrue("uniforms must differ ($y,$c)",
                abs(outA.rgb[(y * W + 17) * 3 + c] - outB.rgb[(y * W + 17) * 3 + c]) > 1e-9f)
            snapped++
        }
        assertTrue("no snap-zone pixels", snapped > 0)
    }

    @Test fun horizonRobustnessDipSnapsToShiftedQuads() {
        // Nearest-r snap on a realistic setup: a 64x64 horizontal step
        // (0.25/0.75) with production analytic kernels, one moving frame
        // shifted a full quad down, r = 0 on two-quad patches along the
        // edge rows. Reference `cpu_accumulate` fetches the nearest quad
        // with the one-quad shift, so every pixel over an r = 0 patch
        // equals the reference-only render exactly, and every pixel over
        // an r = 1 patch equals the all-accepted render exactly.
        val w = 64
        val h = 64
        val qw = w / 2
        val qh = h / 2
        val samples = FloatArray(w * h) { i -> if (i / w < h / 2) 0.25f else 0.75f }
        val gray = RawSrGrayImage(qw, qh,
            FloatArray(qw * qh) { i -> if (i / qw < qh / 2) 0.25f else 0.75f })
        val covariance = RawSrKernelCovariance.covariance(gray, RawSrTuning.forSnr(30.0))
        fun frame(dy: Float, rAt: (qx: Int, qy: Int) -> Float): RawSrBayerMerge.MergeFrame {
            val flow = RawSrAlignmentField(w, h, 1, w, h,
                List(w * h) { RawSrTileFlow(0f, 0f, 0f, dy, 0f, true) })
            val robust = RawSrRobustness.FrameRobustness(
                qw, qh, FloatArray(qw * qh) { i -> rAt(i % qw, i / qw) }, IntArray(qw * qh))
            return RawSrBayerMerge.MergeFrame(
                w, h, samples, BayerPattern.RGGB, 0, 0, covariance, flow, robust)
        }
        val patches = { qx: Int, qy: Int -> if (qy in 14..15 && (qx / 2) % 2 == 0) 0f else 1f }
        val ref = frame(0f) { _, _ -> 1f }
        val outMixed = RawSrBayerMerge.merge(ref, listOf(frame(2.0f, patches)))
        val outReject = RawSrBayerMerge.merge(ref, emptyList(), referenceOnly = true)
        val outAccept = RawSrBayerMerge.merge(ref, listOf(frame(2.0f) { _, _ -> 1f }))
        var rejected = 0
        var accepted = 0
        for (y in 30..33) for (x in 8..23) for (c in 0..2) {
            val qx = floor((x + 0.5) / 2.0 - 1.0).toInt()
            val qy = floor((y + 0.5) / 2.0 - 1.0).toInt()
            val m = outMixed.rgb[(y * w + x) * 3 + c]
            if (patches(qx, qy) == 0f) {
                assertEquals("reject patch ($x,$y,$c)", outReject.rgb[(y * w + x) * 3 + c], m, 0f)
                rejected++
            } else {
                assertEquals("accept patch ($x,$y,$c)", outAccept.rgb[(y * w + x) * 3 + c], m, 0f)
                accepted++
            }
        }
        assertTrue("no reject-patch pixels", rejected > 0)
        assertTrue("no accept-patch pixels", accepted > 0)
        // Vacuous unless the moving frame genuinely changes the blend
        // where accepted: the full-quad downward shift crosses the step.
        var differs = false
        for (y in 30..33) for (x in 8..23) for (c in 0..2) {
            if (abs(outAccept.rgb[(y * w + x) * 3 + c] - outReject.rgb[(y * w + x) * 3 + c]) > 1e-4f) differs = true
        }
        assertTrue("moving frame must change the blend", differs)
    }

    @Test fun srGridMatchesMosaicPlannerAndReproducesUniform() {
        // SR output (shared √2 grid): the linear grid is pixel-identical
        // to the mosaic SR grid while kernels/flow/robustness stay
        // source-anchored (Rc/support keep quad shape); uniform scenes
        // reproduce exactly per channel.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(
            robustness = robust { _, _ -> 1f }, scene = constScene(0.3f, 0.5f, 0.4f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving), scale = RawSrLinearScale.SR)
        val mosaic = MosaicSrReconstructor.planTarget(W, H)
        assertEquals(mosaic.width, out.width)
        assertEquals(mosaic.height, out.height)
        // Pinned: floor-to-even √2 of 32x24.
        assertEquals(44, out.width)
        assertEquals(32, out.height)
        assertEquals(QW, out.rc.width)
        assertEquals(QH, out.rc.height)
        assertEquals(QW * QH, out.support.size)
        assertEquals(44 * 32 * 3, out.rgb.size)
        assertTrue(out.fallback.none { it })
        for (p in 0 until 44 * 32) {
            assertEquals(0.3, out.rgb[p * 3].toDouble(), 1e-6)
            assertEquals(0.5, out.rgb[p * 3 + 1].toDouble(), 1e-6)
            assertEquals(0.4, out.rgb[p * 3 + 2].toDouble(), 1e-6)
        }
    }

    @Test fun srStepEdgeTransitionsMonotonically() {
        // Static burst on a horizontal step: the SR green channel must
        // climb monotonically through the transition (no stride/mapping
        // scramble between the output lattice and source taps).
        val w = 32
        val h = 24
        val samples = FloatArray(w * h) { i -> if (i / w < h / 2) 0.25f else 0.75f }
        fun frame(): RawSrBayerMerge.MergeFrame {
            val flow = RawSrAlignmentField(w, h, 1, w, h,
                List(w * h) { RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true) })
            val robust = RawSrRobustness.FrameRobustness(
                w / 2, h / 2, FloatArray(w / 2 * h / 2) { 1f }, IntArray(w / 2 * h / 2))
            return RawSrBayerMerge.MergeFrame(w, h, samples, BayerPattern.RGGB, 0, 0,
                isoCovariance(w / 2, h / 2), flow, robust)
        }
        val out = RawSrBayerMerge.merge(
            frame().copy(flow = null, robustness = null), listOf(frame()), scale = RawSrLinearScale.SR)
        val planned = RawSrBayerMerge.planTarget(w, h, RawSrLinearScale.SR)
        assertEquals(planned.first, out.width)
        assertEquals(planned.second, out.height)
        for (x in 0 until out.width) {
            var prev = -1.0
            for (y in 0 until out.height) {
                val g = out.rgb[(y * out.width + x) * 3 + 1].toDouble()
                assertTrue("column $x dips at row $y ($prev -> $g)", g + 1e-9 >= prev)
                prev = g
            }
        }
    }

    @Test fun srPlannerUnifiesBothPaths() {
        // The shared planner resolves identical dims for both paths: a
        // 12MP source lands at 5768x4326 (~25MP) on either, and X1/NATIVE
        // reproduce the source grid exactly.
        val linear = RawSrBayerMerge.planTarget(4080, 3060, RawSrLinearScale.SR)
        assertEquals(5768, linear.first)
        assertEquals(4326, linear.second)
        val mosaic = MosaicSrReconstructor.planTarget(4080, 3060)
        assertEquals(linear.first, mosaic.width)
        assertEquals(linear.second, mosaic.height)
        assertEquals(4080 to 3060, RawSrBayerMerge.planTarget(4080, 3060, RawSrLinearScale.X1))
        try {
            RawSrBayerMerge.planTarget(33, 24, RawSrLinearScale.SR)
            fail("odd source width must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun outOfBoundsFramePreservesAccumulatorsAndCountsSupport() {
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val oobFlow = field(flowAt = { _, _ -> 100f to 0f })
        val moving = sceneFrame(flow = oobFlow, scene = constScene(0.9f, 0.1f, 0.8f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val base = refOnlyOf(ref)
        assertArrayEquals(base.rgb, out.rgb, 0f)
        assertTrue(out.oobCount.all { it == 1 })
        // The frame lands out of bounds and contributes nothing, so the
        // output stays exactly the reference with a clean mask (the
        // reference supports every pixel; Rc is diagnostic only).
        assertTrue(out.fallback.none { it })
        assertTrue(out.denominator.all { it > 0f })
    }

    @Test fun motionDiscontinuityMergesWithoutSkip() {
        // Two tile columns disagreeing by 5 quads (0 vs +10 raw px).
        // Reference Alg. 4 defines no motion-edge stop: each pixel merges
        // under its own containing tile's flow (px = int(lr_x//tile_size),
        // no blending — tile 0 reads pure 0, tile 1 pure +10).
        // The step scene (dark left of x = 32, bright right) makes the two
        // tiles disagree in content: tile-0 pixels resolve like the
        // reference-only image while tile-1 interior pixels pull bright
        // shifted taps and differ — proving the moving frame merged on both
        // sides of the discontinuity. Reliability is not a merge input (no
        // zero-shift fallback), so the unreliable twin must merge identically.
        val w = 64
        val h = 32
        fun step(sx: Int, sy: Int, color: CfaColor): Float = if (sx < 32) 0.2f else 0.8f
        val ref = sceneFrame(w, h, scene = ::step)
        val alone = RawSrBayerMerge.merge(ref, emptyList(), referenceOnly = true)
        for (reliable in listOf(true, false)) {
            val mov = sceneFrame(w, h, scene = ::step,
                flow = field(64, 32, 16,
                    flowAt = { tx, _ -> if (tx == 0) 0f to 0f else 10f to 0f },
                    reliable = reliable))
            val out = RawSrBayerMerge.merge(ref, listOf(mov))
            var same = 0
            var moved = 0
            for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
                val v = out.rgb[(y * w + x) * 3 + c]
                val a = alone.rgb[(y * w + x) * 3 + c]
                val baseX = x + 0.5
                if (baseX in 4.0..7.0) {
                    // Tile 0 interior (flow pure 0): identical computation
                    // to reference-only up to float summation order.
                    assertEquals("tile-0 pixel ($x,$y,$c) reliable=$reliable", a, v, 1e-6f)
                    same++
                } else if (baseX in 24.5..28.5) {
                    // Tile 1 interior (pure +10px flow, in bounds): shifted
                    // taps read the bright side while tile 0 would read dark.
                    assertTrue("tile-1 pixel ($x,$y,$c) v=$v a=$a reliable=$reliable did not move",
                        abs(v - a) > 1e-3f)
                    moved++
                }
            }
            assertTrue("no tile-0 pixels reliable=$reliable", same > 0)
            assertTrue("no tile-1 pixels reliable=$reliable", moved > 0)
        }
        // Control: a uniform 10px shift (no disagreement) still merges
        // where in bounds — no magnitude gate exists either.
        val uni = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f),
            flow = field(64, 32, flowAt = { _, _ -> 10f to 0f }))
        val merged = RawSrBayerMerge.merge(ref, listOf(uni))
        assertTrue(merged.denominator.any { it > 1e-8f })
    }

    @Test fun mergePassesNonfiniteNeighbourTiles() {
        // One NaN tile beside agreeing tiles: the nearest lookup reads the
        // containing tile (0,0) directly — neighbours never enter — so
        // pixel (4,4) still merges (denominator carries moving weight, no
        // OOB bump): a NaN neighbour poisons nothing, with the robustness
        // map as the misalignment backstop.
        val w = 32
        val h = 32
        val ref = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f))
        val columns = 4
        val rows = 4
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            if (tx == 1 && ty == 1) RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0f, false)
            else RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(32, 32, 8, columns, rows, tiles)
        val mov = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f), flow = flow)
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        // Pixel (4,4) sits in tile (0,0) next to the NaN tile: it must merge.
        val p = 4 * w + 4
        assertTrue(out.denominator[p * 3] > 1e-8f)
        assertEquals(0, out.oobCount[p])
    }

    @Test fun mergeIgnoresUnreliableTiles() {
        // A wild flow vector on an UNRELIABLE tile never leaks into
        // neighbours under the nearest lookup: pixel (4,4) reads its own
        // tile (0,0), so every tile still fuses. Reference is uniform 0.4,
        // moving uniform 0.6: any moving contribution pulls the mean
        // clearly above reference-only. Reliability is not a merge input
        // (no zero-shift fallback), so the wild tile merges where its own
        // taps land.
        val w = 32
        val h = 32
        val ref = sceneFrame(w, h, scene = constScene(0.4f, 0.4f, 0.4f))
        val columns = 4
        val rows = 4
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            if (tx == 1 && ty == 1) RawSrTileFlow(0f, 0f, 9f, -7f, 0f, false)
            else RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(32, 32, 8, columns, rows, tiles)
        val mov = sceneFrame(w, h, scene = constScene(0.6f, 0.6f, 0.6f), flow = flow)
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        // Pixel (4,4) in tile (0,0): merges under its own zero flow.
        val p = 4 * w + 4
        assertTrue(out.denominator[p * 3] > 1e-8f)
        assertEquals(0, out.oobCount[p])
        assertTrue("mean=${channelMean(out, 0)}", channelMean(out, 0) > 0.45)
    }

    @Test fun nearestSkipsCensoredTaps() {        // 4x4 RGGB, every tap clipped except one clean green tap at (2,1):
        // sourcing the clipped blue tap (1,1) must resolve green through the
        // clean tap, with red/blue denominators at zero — a clipped white
        // tap must never become a fallback value.
        val w = 4
        val h = 4
        val samples = FloatArray(w * h) { 0.999f }
        samples[1 * w + 2] = 0.5f
        val frame = RawSrBayerMerge.MergeFrame(
            w, h, samples, BayerPattern.RGGB, 0, 0,
            isoCovariance(w / 2, h / 2), null, null)
        val nearNum = DoubleArray(3)
        val nearDen = DoubleArray(3)
        RawSrBayerMerge.accumulateNearest(frame, 1.0, 1.0, 1.0, 0, w, h, nearNum, nearDen)
        assertEquals(0.0, nearDen[0], 0.0)
        assertEquals(1.0, nearDen[1], 0.0)
        assertEquals(0.5, nearNum[1], 0.0)
        assertEquals(0.0, nearDen[2], 0.0)
    }

    @Test fun chromaDeweightLeavesGreenChannelBitwiseIdentical() {
        // Textured scene with a subpixel-shifted moving frame: R/B taps gate
        // on green disagreement, but green taps never gate — the green
        // channel must be bitwise identical with and without ChromaParams.
        fun textured(sx: Int, sy: Int, color: CfaColor): Float =
            0.15f + ((sx * 31 + sy * 17) % 23) / 23f * 0.5f + color.ordinal * 0.05f
        val ref = sceneFrame(scene = ::textured)
        val flowDx = field(flowAt = { _, _ -> 0.35f to -0.2f })
        val plain = sceneFrame(flow = flowDx, scene = ::textured)
        val gated = sceneFrame(
            flow = flowDx, chroma = RawSrBayerMerge.ChromaParams(0.0, 1e-6), scene = ::textured)
        val outPlain = RawSrBayerMerge.merge(ref, listOf(plain))
        val outGated = RawSrBayerMerge.merge(ref, listOf(gated))
        for (p in 0 until W * H) {
            assertEquals("green bitwise ($p)",
                outPlain.rgb[p * 3 + 1].toDouble(), outGated.rgb[p * 3 + 1].toDouble(), 0.0)
        }
        var maxRb = 0.0
        for (p in 0 until W * H) for (c in listOf(0, 2)) {
            maxRb = maxOf(maxRb,
                abs(outPlain.rgb[p * 3 + c] - outGated.rgb[p * 3 + c]).toDouble())
        }
        assertTrue("R/B must gate somewhere on texture, maxRb=$maxRb", maxRb > 1e-6)
    }

    @Test fun chromaDeweightIsNearIdentityOnUniformScene() {
        // Uniform scene: localGreen == targetGreen everywhere, so d == 0 and
        // the factor rounds to exactly 1 — gated output stays within float
        // dust of the legacy path on every channel.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val plain = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val gated = sceneFrame(
            chroma = RawSrBayerMerge.ChromaParams(0.0, 1e-6), scene = constScene(0.3f, 0.5f, 0.4f))
        val outPlain = RawSrBayerMerge.merge(ref, listOf(plain))
        val outGated = RawSrBayerMerge.merge(ref, listOf(gated))
        assertTrue("uniform gated must match legacy",
            maxAbsDiff(outPlain, outGated, border = 2) < 1e-9)
    }

    @Test fun chromaDeweightSuppressesRedBleedAcrossGreenEdge() {
        // Green step edge (left 0.2 / right 0.8), red uniform: a moving frame
        // shifted left splats left-side red taps onto right-side outputs.
        // Their local green (0.2) disagrees with the target green, so the
        // gated red denominator must shrink at edge pixels.
        fun edge(sx: Int, sy: Int, color: CfaColor): Float = when (color) {
            CfaColor.RED -> 0.5f
            CfaColor.GREEN -> if (sx < W / 2) 0.2f else 0.8f
            CfaColor.BLUE -> 0.5f
        }
        val ref = sceneFrame(scene = ::edge)
        val flowLeft = field(flowAt = { _, _ -> -0.75f to 0f })
        val plain = sceneFrame(flow = flowLeft, scene = ::edge)
        val gated = sceneFrame(
            flow = flowLeft, chroma = RawSrBayerMerge.ChromaParams(0.0, 1e-6), scene = ::edge)
        val outPlain = RawSrBayerMerge.merge(ref, listOf(plain))
        val outGated = RawSrBayerMerge.merge(ref, listOf(gated))
        var shrunk = 0
        for (y in 2 until H - 2) for (x in W / 2 until W / 2 + 4) {
            val p = y * W + x
            if (outGated.denominator[p * 3] < outPlain.denominator[p * 3] - 1e-9) shrunk++
        }
        assertTrue("gated red denominators must shrink at the edge, shrunk=$shrunk", shrunk > 0)
    }

    @Test fun missingSupportDividesToZero() {        val w = 12
        val h = 12
        // Poison the reference inside a 4x4 block; the moving frame is fully
        // rejected there, so no usable support remains on any path. The
        // reference defines no fallback branch: zero support divides to 0
        // (the reference NaN blacked downstream) with the flag set.
        val refSamples = FloatArray(w * h) { 0.5f }
        for (y in 4..7) for (x in 4..7) refSamples[y * w + x] = Float.NaN
        val qw = w / 2
        val qh = h / 2
        fun frame(samples: FloatArray, r: (x: Int, y: Int) -> Float): RawSrBayerMerge.MergeFrame {
            return RawSrBayerMerge.MergeFrame(w, h, samples, BayerPattern.RGGB, 0, 0,
                isoCovariance(qw, qh), field(w, h), robust(qw, qh, r))
        }
        val ref = frame(refSamples) { _, _ -> 1f }
        val moving = frame(FloatArray(w * h) { 0.5f }) { x, y -> if (x in 1..4 && y in 1..4) 0f else 1f }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        assertTrue(out.rgb.all { it.isFinite() })
        assertTrue(out.numerator.all { it.isFinite() })
        assertTrue(out.denominator.all { it.isFinite() })
        assertTrue(out.rc.values.all { it.isFinite() })
        assertTrue("center must fall back", out.fallback[5 * w + 5])
        for (c in 0..2) assertEquals(0f, out.rgb[(5 * w + 5) * 3 + c], 0f)
        assertTrue("far corner must not fall back", !out.fallback[0])
        // The fallback set is exactly the den <= eps set: no support
        // overwrite exists in the reference path.
        for (p in 0 until w * h) {
            val anyMissing = (0..2).any { c -> out.denominator[p * 3 + c] <= 1e-8f }
            assertEquals("fallback set ($p)", anyMissing, out.fallback[p])
        }
    }

    @Test fun partialSupportBlendsGhostContent() {
        // Single moving frame with fractional robustness (0.3) over a block
        // and ghost content. The reference defines no support overwrite, so
        // the block interior takes the plain kernel blend of reference and
        // ghost ((0.3 + 0.9*0.3)/1.3, (0.5 + 0.1*0.3)/1.3, (0.4 + 0.8*0.3)/1.3)
        // with a clean mask — never a reference-only overwrite. Nearest r
        // fetch snaps at quad borders (pinned by
        // robustnessStepSnapsToShiftedQuad), so the exact blend is
        // asserted on the guaranteed interior (every pixel reads
        // r = 0.3: x in 15..19 → quads 6..8, y in 11..13 → quads 4..5).
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0.3f else 1f },
            scene = constScene(0.9f, 0.1f, 0.8f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val expected = doubleArrayOf(0.57 / 1.3, 0.53 / 1.3, 0.64 / 1.3)
        for (y in 11..13) for (x in 15..19) for (c in 0..2) {
            assertEquals("($x,$y,$c)", expected[c], out.rgb[(y * W + x) * 3 + c].toDouble(), 1e-6)
        }
        assertTrue("block must not fall back", (10..13).all { y -> (14..19).all { x -> !out.fallback[y * W + x] } })
        assertTrue("full-support corner must not fall back", !out.fallback[0])
    }

    @Test fun rcAccumulationIsExactAndSupportIsOnePlusRc() {        val qw = 4
        val qh = 3
        val r1 = FloatArray(qw * qh) { (it + 1) * 0.05f }
        val r2 = FloatArray(qw * qh) { if (it % 3 == 0) 0f else 0.5f }
        fun frame(r: FloatArray): RawSrBayerMerge.MergeFrame {
            val w = qw * 2
            val h = qh * 2
            return RawSrBayerMerge.MergeFrame(w, h, FloatArray(w * h) { 0.4f }, BayerPattern.RGGB, 0, 0,
                isoCovariance(qw, qh), field(w, h, 2), RawSrRobustness.FrameRobustness(qw, qh, r, IntArray(r.size)))
        }
        val ref = frame(FloatArray(qw * qh) { 1f })
        val out = RawSrBayerMerge.merge(ref, listOf(frame(r1), frame(r2)))
        var expected = RawSrRobustness.accumulate(null,
            RawSrRobustness.FrameRobustness(qw, qh, r1, IntArray(r1.size)))
        expected = RawSrRobustness.accumulate(expected,
            RawSrRobustness.FrameRobustness(qw, qh, r2, IntArray(r2.size)))
        assertArrayEquals(expected.values, out.rc.values, 0f)
        for (i in expected.values.indices) {
            assertEquals(1f + expected.values[i], out.support[i], 0f)
        }
        val only = refOnlyOf(ref)
        assertTrue(only.rc.values.all { it == 0f })
        assertTrue(only.support.all { it == 1f })
    }

    @Test fun rcAndSupportAccumulateFrameSums() {
        // Rc is the plain finite-sanitized frame sum (like the reference
        // accumulated-robustness map — no per-frame clamp exists), and
        // support reads 1 + Rc: two full frames give Rc = 2 / support = 3,
        // two half frames give Rc = 1 / support = 2.
        val qw = 4
        val qh = 3
        val w = qw * 2
        val h = qh * 2
        fun frame(r: Float): RawSrBayerMerge.MergeFrame =
            RawSrBayerMerge.MergeFrame(w, h, FloatArray(w * h) { 0.4f }, BayerPattern.RGGB, 0, 0,
                isoCovariance(qw, qh), field(w, h, 2),
                RawSrRobustness.FrameRobustness(qw, qh, FloatArray(qw * qh) { r }, IntArray(qw * qh)))
        val ref = frame(1f)
        val out = RawSrBayerMerge.merge(ref, listOf(frame(1f), frame(1f)))
        assertTrue(out.rc.values.all { it == 2f })
        assertTrue(out.support.all { it == 3f })
        val half = RawSrBayerMerge.merge(ref, listOf(frame(0.5f), frame(0.5f)))
        assertTrue(half.rc.values.all { it == 1f })
        assertTrue(half.support.all { it == 2f })
    }

    @Test fun channelEvidenceTracksMovingSupport() {
        // Per-channel moving-frame evidence bits (Sabre support.g/b
        // analogue): full support sets all three bits; a fully rejected
        // frame sets none; reference-only mode reads zero everywhere.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        assertEquals(W * H, out.channelEvidence.size)
        for (y in 2 until H - 2) for (x in 2 until W - 2) {
            assertEquals("evidence ($x,$y)", 0b111.toByte(), out.channelEvidence[y * W + x])
        }
        val rejected = sceneFrame(
            robustness = robust { _, _ -> 0f }, scene = constScene(0.3f, 0.5f, 0.4f))
        val outRejected = RawSrBayerMerge.merge(ref, listOf(rejected))
        assertTrue(outRejected.channelEvidence.all { it == 0.toByte() })
        val only = refOnlyOf(ref)
        assertTrue(only.channelEvidence.all { it == 0.toByte() })
    }

    @Test fun mergeIsDeterministicRunToRun() {
        fun textured(sx: Int, sy: Int, color: CfaColor): Float =
            0.2f + ((sx * 57 + sy * 23 + color.ordinal * 11) % 41) / 41f * 0.6f
        val ref = sceneFrame(scene = ::textured)
        val moving = listOf(0.3f to -0.2f, -0.4f to 0.35f).map { (dx, dy) ->
            sceneFrame(flow = field(flowAt = { _, _ -> dx to dy }), scene = ::textured)
        }
        val first = RawSrBayerMerge.merge(ref, moving)
        val second = RawSrBayerMerge.merge(ref, moving)
        assertArrayEquals(first.rgb, second.rgb, 0f)
        assertArrayEquals(first.numerator, second.numerator, 0f)
        assertArrayEquals(first.denominator, second.denominator, 0f)
        assertArrayEquals(first.rc.values, second.rc.values, 0f)
        assertArrayEquals(first.support, second.support, 0f)
        assertArrayEquals(first.oobCount, second.oobCount)
        assertArrayEquals(first.fallback, second.fallback)
        assertArrayEquals(first.channelEvidence, second.channelEvidence)
    }

    @Test fun poisonedInputsStayFinite() {
        val refSamples = FloatArray(W * H) { 0.4f }
        refSamples[10] = Float.NaN
        refSamples[20] = Float.POSITIVE_INFINITY
        val ref = RawSrBayerMerge.MergeFrame(W, H, refSamples, BayerPattern.RGGB, 0, 0,
            isoCovariance(), field(), robust())
        val badCovariance = isoCovariance().values
        badCovariance[0] = Float.NaN
        badCovariance[7] = Float.POSITIVE_INFINITY
        val badFlow = field(flowAt = { tx, ty ->
            when {
                tx == 0 && ty == 0 -> Float.NaN to 0f
                tx == 1 && ty == 0 -> 0f to Float.POSITIVE_INFINITY
                else -> 0.1f to -0.1f
            }
        })
        val badRobust = robust { x, y ->
            when {
                x == 0 && y == 0 -> Float.NaN
                x == 1 && y == 0 -> Float.POSITIVE_INFINITY
                else -> 1f
            }
        }
        val movingSamples = FloatArray(W * H) { 0.4f }
        movingSamples[30] = Float.NaN
        movingSamples[40] = Float.NEGATIVE_INFINITY
        val moving = RawSrBayerMerge.MergeFrame(W, H, movingSamples, BayerPattern.RGGB, 0, 0,
            RawSrKernelCovariance.MatrixField(QW, QH, badCovariance), badFlow, badRobust)
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        assertTrue(out.rgb.all { it.isFinite() })
        assertTrue(out.numerator.all { it.isFinite() })
        assertTrue(out.denominator.all { it.isFinite() })
        assertTrue(out.rc.values.all { it.isFinite() })
        assertTrue(out.support.all { it.isFinite() })
    }

    @Test fun memoryScalesConstantlyAndCleansUpOnFailureOrCancel() {
        fun burst(frames: Int): Pair<RawSrBayerMerge.MergeFrame, List<RawSrBayerMerge.MergeFrame>> {
            val ref = sceneFrame(scene = constScene(0.3f, 0.4f, 0.5f))
            val moving = List(frames - 1) { i ->
                sceneFrame(flow = field(flowAt = { _, _ -> i * 0.05f to 0f }), scene = constScene(0.3f, 0.4f, 0.5f))
            }
            return ref to moving
        }
        var peak = -1L
        for (frames in listOf(2, 8, 15, 30)) {
            val (ref, moving) = burst(frames)
            val memory = RawSrTextureMemory()
            val out = RawSrBayerMerge.merge(ref, moving, memory = memory)
            assertTrue(out.rgb.all { it.isFinite() })
            assertEquals(memory.liveBytes, memory.peakBytes)
            if (peak < 0) peak = memory.peakBytes else assertEquals("frames=$frames", peak, memory.peakBytes)
        }
        val (ref, moving) = burst(4)
        val failed = RawSrTextureMemory()
        try {
            val bad = moving + sceneFrame(w = W + 2, h = H, scene = constScene(0.1f, 0.1f, 0.1f))
            RawSrBayerMerge.merge(ref, bad, memory = failed)
            fail("mismatched dimensions must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals(0L, failed.liveBytes)
        }
        val cancelled = RawSrTextureMemory()
        try {
            RawSrBayerMerge.merge(ref, moving, memory = cancelled, isCancelled = { rows -> rows >= 5 })
            fail("cancellation must throw")
        } catch (e: CancellationException) {
            assertEquals(0L, cancelled.liveBytes)
        }
    }

    @Test fun integrationWithValidatedRobustnessAndCovarianceOracles() {
        val layoutW = 34
        val layoutH = 26
        val originX = 1
        val originY = 0
        val crop = RawCrop(0, 0, W, H)
        val profile = ImmutableDoubleValues(doubleArrayOf(0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0))
        val tuning = RawSrTuning.forSnr(18.0)
        val config = RawSrAlignmentConfig()
        fun packedFrame(seed: Int): RawSrPackedFrame {
            val random = Random(seed)
            val noise = FloatArray((layoutW + 1) * (layoutH + 1)) { random.nextGaussian().toFloat() * 5f }
            val rowStride = layoutW * 2
            val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
            for (sy in 0 until layoutH) for (sx in 0 until layoutW) {
                val base = 1500 + ((sx * 79 + sy * 43) % 101)
                plane.putShort(sy * rowStride + sx * 2, (base + noise[sy * layoutW + sx]).toInt().toShort())
            }
            return RawSrPackedFrame(
                plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, originX, originY),
                crop, RawNormalization(BayerPattern.GRBG, listOf(64f, 64f, 64f, 64f), 4000f),
                null, profile)
        }
        fun mergeFrame(packed: RawSrPackedFrame, r: RawSrRobustness.FrameRobustness): RawSrBayerMerge.MergeFrame {
            val unpacked = RawSensorUnpacker.unpackNormalized(
                packed.uploadInput().buffer, RawPlaneLayout(layoutW, layoutH, layoutW * 2, 2, originX, originY),
                RawNormalization(BayerPattern.GRBG, listOf(64f, 64f, 64f, 64f), 4000f), crop)
            val guide = RawSrCovarianceGuide.guide(packed)
            val covariance = RawSrKernelCovariance.covariance(guide.gray, tuning)
            return RawSrBayerMerge.MergeFrame(
                unpacked.width, unpacked.height, unpacked.values,
                unpacked.pattern, unpacked.sensorCropLeft, unpacked.sensorCropTop,
                covariance, field(W, H), r)
        }
        val refPacked = packedFrame(7)
        val refGuide = RawSrRobustness.linearGuide(refPacked)
        val zeroFlow = field(W, H)
        val frames = listOf(refPacked, packedFrame(99), packedFrame(1234))
        val guides = frames.map { RawSrRobustness.linearGuide(it) }
        val robustness = guides.drop(1).map { RawSrRobustness.evaluate(refGuide, it, zeroFlow, tuning, config) }
        val mergeFrames = listOf(
            mergeFrame(refPacked, robust()),
            mergeFrame(frames[1], robustness[0]),
            mergeFrame(frames[2], robustness[1]))
        val out = RawSrBayerMerge.merge(mergeFrames[0], mergeFrames.drop(1))
        val base = RawSrBayerMerge.merge(mergeFrames[0], emptyList(), referenceOnly = true)
        assertTrue(out.rgb.all { it.isFinite() })
        // Static noisy burst: the validated robustness oracle keeps most quads.
        assertTrue("rcMean=${out.rc.values.average()}", out.rc.values.average() >= 1.5)
        var sum = 0.0
        var n = 0
        for (y in 2 until H - 2) for (x in 2 until W - 2) for (c in 0..2) {
            sum += abs(out.rgb[(y * W + x) * 3 + c] - base.rgb[(y * W + x) * 3 + c])
            n++
        }
        assertTrue("meanAbsDiff=${sum / n}", sum / n <= 0.01)
    }

    @Test fun channelEvidenceBitsMatchDenominatorSupport() {
        // Pure-function contract: bit c of pixel p is set iff den[3p+c]
        // strictly exceeds EPS (exactly-EPS reads 0). Exact at serial and
        // sharded worker counts, straddling spans included.
        val den = doubleArrayOf(
            1.0, 1.0, 1.0, // 0b111
            0.0, 2.0, 0.0, // 0b010
            0.0, 0.0, 0.0, // 0b000
            3.0, 0.0, 4.0, // 0b101
            RawSrBayerMerge.EPS, RawSrBayerMerge.EPS, RawSrBayerMerge.EPS // 0b000
        )
        val expected = byteArrayOf(
            0b111.toByte(), 0b010.toByte(), 0b000.toByte(), 0b101.toByte(), 0b000.toByte())
        for (workers in intArrayOf(1, 2, 3, 4, 5)) {
            RawSrWorkers.overrideCount = workers
            try {
                assertArrayEquals("workers=$workers", expected,
                    RawSrBayerMerge.computeChannelEvidence(den, 5))
            } finally {
                RawSrWorkers.overrideCount = null
            }
        }
        try {
            RawSrBayerMerge.computeChannelEvidence(DoubleArray(4), 2)
            fail("mismatched denominator size must fail")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun channelEvidenceIsRaceFreeAtAnyWorkerCount() {
        // Regression: the evidence loop once sharded over channels, so a
        // pixel's three read-modify-write updates could split across two
        // shards (any span not a multiple of 3: counts 5/9/13 below) and
        // lose a bit to a lost update. Pixel sharding gives one writer per
        // pixel, so full support reads 0b111 on every run at every count.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        for (workers in intArrayOf(1, 5, 9, 13)) {
            RawSrWorkers.overrideCount = workers
            try {
                repeat(20) { iter ->
                    val out = RawSrBayerMerge.merge(ref, listOf(moving))
                    for (y in 2 until H - 2) for (x in 2 until W - 2) {
                        assertEquals("workers=$workers iter=$iter ($x,$y)",
                            0b111.toByte(), out.channelEvidence[y * W + x])
                    }
                }
            } finally {
                RawSrWorkers.overrideCount = null
            }
        }
    }
}

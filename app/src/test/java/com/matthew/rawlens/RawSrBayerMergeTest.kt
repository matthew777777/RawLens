// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs
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
        qw: Int = QW, qh: Int = QH, tileSize: Int = 8,
        flowAt: (tx: Int, ty: Int) -> Pair<Float, Float> = { _, _ -> 0f to 0f },
        reliable: Boolean = true
    ): RawSrAlignmentField {
        val columns = (qw + tileSize - 1) / tileSize
        val rows = (qh + tileSize - 1) / tileSize
        val tiles = List(columns * rows) { i ->
            val (dx, dy) = flowAt(i % columns, i / columns)
            RawSrTileFlow(0f, 0f, dx, dy, 0f, reliable)
        }
        return RawSrAlignmentField(qw, qh, tileSize, columns, rows, tiles)
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
            flow ?: field(w / 2, h / 2),
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
        // and contributes nothing, so the block resolves through the
        // reference kernel quotient alone — never the +0.5 ghost content.
        // Every output must lie within the reference window's own value
        // range: any ghost admixture would exceed its maximum, since every
        // ghost sample sits exactly +0.5 above the reference texture. No
        // fallback trips (the reference supports every pixel).
        fun textured(sx: Int, sy: Int, color: CfaColor): Float = 0.15f + ((sx * 31 + sy * 17) % 23) / 23f * 0.5f
        val ref = sceneFrame(scene = ::textured)
        val inBlock = { x: Int, y: Int -> x in 12..21 && y in 8..15 }
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0f else 1f }
        ) { sx, sy, color -> if (inBlock(sx, sy)) textured(sx, sy, color) + 0.5f else textured(sx, sy, color) }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val refSamples = FloatArray(W * H) { i -> textured(i % W, i / W, BayerPattern.RGGB.colorAt(i % W, i / W)) }
        for (y in 10..13) for (x in 14..19) {
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
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(robustness = robust { x, y -> if (x in 4..11 && y in 3..8) 0f else 1f }) { _, _, _ -> 5.0f }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val base = refOnlyOf(ref)
        for (y in 8..15) for (x in 10..21) for (c in 0..2) {
            assertEquals("($x,$y,$c)", base.rgb[(y * W + x) * 3 + c], out.rgb[(y * W + x) * 3 + c], 0f)
        }
    }

    @Test fun flowTransitionQuiltsAtTileBorder() {
        // Reference Alg. 4 looks the flow up at the nearest tile
        // (`int(lr//tile_size)`): alternating tile-columns shifting 0 vs 1
        // quad px (tileSize 4) resolve each side of a tile border to its own
        // tile's uniform outcome bitwise — the quilt step. A smoothing lookup
        // would land strictly between the endpoints at border pixels instead.
        fun ramp(sx: Int, sy: Int, color: CfaColor): Float = 0.1f + 0.6f * sx.toFloat() / W + color.ordinal * 0.05f
        val ref = sceneFrame(scene = ::ramp)
        val mixed = field(QW, QH, 4, flowAt = { tx, _ -> if (tx % 2 == 0) 0f to 0f else 1f to 0f })
        val uniformA = field(QW, QH, 4, flowAt = { _, _ -> 0f to 0f })
        val uniformB = field(QW, QH, 4, flowAt = { _, _ -> 1f to 0f })
        fun movingWith(flow: RawSrAlignmentField) = sceneFrame(flow = flow, scene = ::ramp)
        val outMixed = RawSrBayerMerge.merge(ref, listOf(movingWith(mixed)))
        val outA = RawSrBayerMerge.merge(ref, listOf(movingWith(uniformA)))
        val outB = RawSrBayerMerge.merge(ref, listOf(movingWith(uniformB)))
        fun rOf(out: RawSrBayerMerge.MergeResult, x: Int, y: Int) = out.rgb[(y * W + x) * 3]
        val y = H / 2
        // Quad 1 (tile 0): exact tile value, equals uniform A.
        assertEquals(rOf(outA, 2, y), rOf(outMixed, 2, y), 0f)
        // Quad 2 (tile 0): nearest-tile lookup reproduces A bitwise here.
        assertEquals(rOf(outA, 5, y), rOf(outMixed, 5, y), 0f)
        // Quad 4 (tile 1): the quilt step — equals uniform B bitwise, while
        // the two uniforms genuinely differ on the ramp.
        assertEquals(rOf(outB, 9, y), rOf(outMixed, 9, y), 0f)
        assertTrue("uniforms must differ", abs(rOf(outA, 9, y) - rOf(outB, 9, y)) > 1e-4)
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
        // under its own nearest tile's flow. The step scene (dark left of
        // x = 24, bright right) makes the two tiles disagree in content:
        // tile-0 pixels resolve like the reference-only image while tile-1
        // interior pixels pull bright shifted taps and differ — proving the
        // moving frame merged on both sides of the discontinuity.
        val w = 32
        val h = 32
        fun step(sx: Int, sy: Int, color: CfaColor): Float = if (sx < 24) 0.2f else 0.8f
        val ref = sceneFrame(w, h, scene = ::step)
        val mov = sceneFrame(w, h, scene = ::step,
            flow = field(16, 16, 8, flowAt = { tx, _ -> if (tx == 0) 0f to 0f else 5f to 0f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        val alone = RawSrBayerMerge.merge(ref, emptyList(), referenceOnly = true)
        var same = 0
        var moved = 0
        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
            val v = out.rgb[(y * w + x) * 3 + c]
            val a = alone.rgb[(y * w + x) * 3 + c]
            val baseX = x + 0.5
            if (baseX in 4.0..12.0) {
                // Tile 0 interior (flow 0): identical computation to
                // reference-only up to float summation order.
                assertEquals("tile-0 pixel ($x,$y,$c)", a, v, 1e-6f)
                same++
            } else if (baseX in 16.5..20.5) {
                // Tile 1 interior (flow +10 px, in bounds): shifted taps
                // read the bright side while tile 0 would read dark.
                assertTrue("tile-1 pixel ($x,$y,$c) v=$v a=$a did not move",
                    abs(v - a) > 1e-3f)
                moved++
            }
        }
        assertTrue("no tile-0 pixels", same > 0)
        assertTrue("no tile-1 pixels", moved > 0)
        // Control: a uniform 5-quad shift (no disagreement) still merges
        // where in bounds — no magnitude gate exists either.
        val uni = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f),
            flow = field(16, 16, 8, flowAt = { _, _ -> 5f to 0f }))
        val merged = RawSrBayerMerge.merge(ref, listOf(uni))
        assertTrue(merged.denominator.any { it > 1e-8f })
    }

    @Test fun mergePassesNonfiniteNeighbourTiles() {
        // One NaN tile beside agreeing tiles: nearest-tile lookup reads only
        // the containing tile, so finite tiles still merge (denominator
        // carries moving weight, no OOB bump) — a NaN neighbour poisons
        // nothing, with the robustness map as the misalignment backstop.
        val w = 32
        val h = 32
        val ref = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f))
        val columns = 2
        val rows = 2
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            if (tx == 1 && ty == 1) RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0f, false)
            else RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(16, 16, 8, columns, rows, tiles)
        val mov = sceneFrame(w, h, scene = constScene(0.5f, 0.5f, 0.5f), flow = flow)
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        // Quad (2,2) sits in tile (0,0) next to the NaN tile: it must merge.
        val p = 4 * w + 4
        assertTrue(out.denominator[p * 3] > 1e-8f)
        assertEquals(0, out.oobCount[p])
    }

    @Test fun mergeIgnoresUnreliableTiles() {
        // A wild flow vector on an UNRELIABLE tile affects only its own
        // tile's pixels under nearest-tile lookup: every other tile still
        // fuses. Reference is uniform 0.4, moving uniform 0.6: any moving
        // contribution pulls the mean clearly above reference-only.
        val w = 32
        val h = 32
        val ref = sceneFrame(w, h, scene = constScene(0.4f, 0.4f, 0.4f))
        val columns = 2
        val rows = 2
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            if (tx == 1 && ty == 1) RawSrTileFlow(0f, 0f, 9f, -7f, 0f, false)
            else RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(16, 16, 8, columns, rows, tiles)
        val mov = sceneFrame(w, h, scene = constScene(0.6f, 0.6f, 0.6f), flow = flow)
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        // Quad (2,2) in tile (0,0): merges under its own finite flow.
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
                isoCovariance(qw, qh), field(qw, qh), robust(qw, qh, r))
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
        // the block takes the plain kernel blend of reference and ghost
        // ((0.3 + 0.9*0.3)/1.3, (0.5 + 0.1*0.3)/1.3, (0.4 + 0.8*0.3)/1.3)
        // with a clean mask — never a reference-only overwrite.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0.3f else 1f },
            scene = constScene(0.9f, 0.1f, 0.8f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val expected = doubleArrayOf(0.57 / 1.3, 0.53 / 1.3, 0.64 / 1.3)
        for (y in 10..13) for (x in 14..19) for (c in 0..2) {
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
                isoCovariance(qw, qh), field(qw, qh), RawSrRobustness.FrameRobustness(qw, qh, r, IntArray(r.size)))
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
                isoCovariance(qw, qh), field(qw, qh),
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
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
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
                covariance, field(QW, QH), r)
        }
        val refPacked = packedFrame(7)
        val refGuide = RawSrRobustness.linearGuide(refPacked)
        val zeroFlow = field(QW, QH)
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
}

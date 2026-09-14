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
 * scene. The merge consumes precomputed precision fields and no tuning object.
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

    private fun isoPrecision(qw: Int = QW, qh: Int = QH, k: Double = K): RawSrKernelCovariance.MatrixField {
        val p = (1.0 / (k * k)).toFloat()
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
        scene: (sx: Int, sy: Int, color: CfaColor) -> Float
    ): RawSrBayerMerge.MergeFrame {
        val samples = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            scene(ox + x, oy + y, pattern.colorAt(ox + x, oy + y))
        }
        return RawSrBayerMerge.MergeFrame(
            w, h, samples, pattern, ox, oy,
            isoPrecision(w / 2, h / 2, k),
            flow ?: field(w / 2, h / 2),
            robustness ?: robust(w / 2, h / 2))
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

    @Test fun fallbackTakesBurstNearestBlendNotReferenceOnly() {
        // One moving frame at r = 0.1: rc = 0.1 < MIN_SUPPORT, so every quad
        // is unsupported and the fallback path backs every pixel. Uniform
        // scenes make kernel and nearest blends coincide at
        // (0.4 + 0.6 * 0.1) / 1.1 = 0.4181818; reference-only would be 0.4.
        val ref = sceneFrame(scene = constScene(0.4f, 0.4f, 0.4f))
        val mov = sceneFrame(
            scene = constScene(0.6f, 0.6f, 0.6f),
            robustness = robust(value = { _, _ -> 0.1f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.all { it })
        // Float32 inputs: 0.4f/0.6f/0.1f are not exact decimals, so allow
        // float rounding (still 40x away from the 0.4 reference-only value).
        val expected = 0.46 / 1.1
        for (c in 0..2) assertEquals(expected, channelMean(out, c), 1e-6)
    }

    @Test fun fallbackPicksNearestMatchingPhaseSample() {
        // Checkerboard reference, fully rejected moving frame: nearest must
        // return the center texel itself (distance 0, unique minimum) where
        // its colour matches, while the reference-only kernel mean would be
        // ~0.5. Proves the pick, not just the blend.
        val checker: (Int, Int, CfaColor) -> Float =
            { x, y, _ -> if ((x + y) % 2 == 0) 1f else 0f }
        val ref = sceneFrame(pattern = BayerPattern.RGGB, scene = checker)
        val mov = sceneFrame(
            pattern = BayerPattern.RGGB,
            scene = constScene(0.9f, 0.9f, 0.9f),
            robustness = robust(value = { _, _ -> 0f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.all { it })
        // (4,4) is RED with value 1; (5,5) is BLUE with value 1; (5,4) is
        // GREEN with value 0 (kernel mean would be ~0.5 either way).
        assertEquals(1.0, out.rgb[(4 * W + 4) * 3 + 0].toDouble(), 1e-9)
        assertEquals(1.0, out.rgb[(5 * W + 5) * 3 + 2].toDouble(), 1e-9)
        assertEquals(0.0, out.rgb[(4 * W + 5) * 3 + 1].toDouble(), 1e-9)
    }

    @Test fun saturatedReferenceFallsBackToReferenceNotNearest() {
        // Saturated reference (1.0) with a low-support moving frame whose
        // samples disagree (0.2): every quad is unsupported (rc = 0.3), so
        // the fallback path must keep the reference value per channel
        // instead of the burst-nearest blend. Clipped sites carry no
        // trustworthy signal, and the unattenuated nearest mean smears
        // misregistered neighbours into highlights (forest 4E measured
        // deviations up to ~1.2 with dark speckle down to 0.19). Without
        // the guard the blend would be (1.0 + 0.2 * 0.3) / 1.3 = 0.8154.
        val ref = sceneFrame(scene = constScene(1.0f, 1.0f, 1.0f))
        val mov = sceneFrame(
            scene = constScene(0.2f, 0.2f, 0.2f),
            robustness = robust(value = { _, _ -> 0.3f }))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.all { it })
        for (y in 2 until H - 2) for (x in 2 until W - 2) for (c in 0..2) {
            assertEquals("($x,$y,$c)", 1.0, out.rgb[(y * W + x) * 3 + c].toDouble(), 1e-6)
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
            assertTrue("seed=$seed ratio=${mergedVar / singleVar}", mergedVar <= 0.25 * singleVar)
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

    @Test fun foregroundOcclusionStaysGhostFreeOnNearestFallback() {
        // Occluded block (robustness 0): the moving frame is fully rejected,
        // so the fallback path backs the block with the reference-anchored
        // burst-nearest value — the zero-shift nearest pick, i.e. the
        // reference texel itself — never the +0.5 ghost content. Nearest is
        // sharper than the old reference-only kernel mean on texture, so the
        // assertion is ghost distance, not kernel equality.
        fun textured(sx: Int, sy: Int, color: CfaColor): Float = 0.15f + ((sx * 31 + sy * 17) % 23) / 23f * 0.5f
        val ref = sceneFrame(scene = ::textured)
        val inBlock = { x: Int, y: Int -> x in 12..21 && y in 8..15 }
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0f else 1f }
        ) { sx, sy, color -> if (inBlock(sx, sy)) textured(sx, sy, color) + 0.5f else textured(sx, sy, color) }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val refSamples = FloatArray(W * H) { i -> textured(i % W, i / W, BayerPattern.RGGB.colorAt(i % W, i / W)) }
        for (y in 10..13) for (x in 14..19) {
            assertTrue("fallback ($x,$y)", out.fallback[y * W + x])
            for (c in 0..2) {
                val v = out.rgb[(y * W + x) * 3 + c]
                // The pick must be exactly one of the reference window's
                // same-colour texels (zero shift ⇒ center window). The ghost
                // frame (r = 0) contributes nothing, so ghost content cannot
                // appear: every ghost sample is exactly +0.5 above any ref
                // sample, and v is bit-identical to a ref texel.
                var ok = false
                for (oy in -1..1) for (ox in -1..1) {
                    val tx = x + ox
                    val ty = y + oy
                    if (BayerPattern.RGGB.colorAt(tx, ty).ordinal == c &&
                        refSamples[ty * W + tx] == v) ok = true
                }
                assertTrue("ref-anchored nearest ($x,$y,$c) v=$v", ok)
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

    @Test fun flowTransitionBlendsAcrossTileBorder() {
        // Tile borders must not reach the merge (the shadow quilt measured on
        // device): bilinear flow blends across them instead of stepping.
        // Alternating tile-columns shift 0 vs 1 quad px (tileSize 4) on a
        // monotonic ramp, so a blended shift lands strictly between the two
        // uniform outcomes, while edge-clamped quads reproduce their tile.
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
        // Quad 1 (tile 0, edge-clamped): exact tile value, equals uniform A.
        assertEquals(rOf(outA, 2, y), rOf(outMixed, 2, y), 0f)
        // Quad 2 (blend 0.875/0.125): strictly between the uniforms.
        // Nearest-tile flow reproduced A exactly here (the quilt mechanism).
        val m = rOf(outMixed, 5, y)
        val a = rOf(outA, 5, y)
        val b = rOf(outB, 5, y)
        assertTrue("blend m=$m a=$a b=$b", m > a + 1e-4 && m < b - 1e-4)
    }

    @Test fun outOfBoundsFramePreservesAccumulatorsAndCountsSupport() {
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val oobFlow = field(flowAt = { _, _ -> 100f to 0f })
        val moving = sceneFrame(flow = oobFlow, scene = constScene(0.9f, 0.1f, 0.8f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val base = refOnlyOf(ref)
        assertArrayEquals(base.rgb, out.rgb, 0f)
        assertTrue(out.oobCount.all { it == 1 })
        // Injected full support (Rc = 1) must not trigger the support
        // overwrite even though the frame lands out of bounds and contributes
        // nothing: the output stays exactly the reference with a clean mask.
        assertTrue(out.fallback.none { it })
        assertTrue(out.denominator.all { it > 0f })
    }

    @Test fun missingSupportFallsBackToReferenceOnly() {
        val w = 12
        val h = 12
        // Poison the reference inside a 4x4 block; the moving frame is fully
        // rejected there, so no usable support remains on any path.
        val refSamples = FloatArray(w * h) { 0.5f }
        for (y in 4..7) for (x in 4..7) refSamples[y * w + x] = Float.NaN
        val qw = w / 2
        val qh = h / 2
        fun frame(samples: FloatArray, r: (x: Int, y: Int) -> Float): RawSrBayerMerge.MergeFrame {
            return RawSrBayerMerge.MergeFrame(w, h, samples, BayerPattern.RGGB, 0, 0,
                isoPrecision(qw, qh), field(qw, qh), robust(qw, qh, r))
        }
        val ref = frame(refSamples) { _, _ -> 1f }
        val moving = frame(FloatArray(w * h) { 0.5f }) { x, y -> if (x in 1..4 && y in 1..4) 0f else 1f }
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        assertTrue(out.rgb.all { it.isFinite() })
        assertTrue(out.numerator.all { it.isFinite() })
        assertTrue(out.denominator.all { it.isFinite() })
        assertTrue(out.rc.values.all { it.isFinite() })
        assertTrue("center must fall back", out.fallback[5 * w + 5])
        assertTrue("far corner must not fall back", !out.fallback[0])
        // The fallback set is the den <= eps set plus the support-overwrite
        // set (quads with Rc below one frame-equivalent), per contract §9.
        for (p in 0 until w * h) {
            val anyMissing = (0..2).any { c -> out.denominator[p * 3 + c] <= 1e-8f }
            val unsupported = out.rc.values[(p / w / 2) * qw + (p % w / 2)] < RawSrBayerMerge.MIN_SUPPORT
            assertEquals("fallback set ($p)", anyMissing || unsupported, out.fallback[p])
        }
    }

    @Test fun partialSupportFallsBackToBurstNearest() {
        // Single moving frame with fractional robustness (0.3) over a block
        // and ghost content: quad support Rc = 0.3 < 1, so the block takes
        // the fallback path with the mask set instead of a kernel
        // reference/ghost blend (Stacker parity: overwrite where accumulated
        // robustness is below one frame-equivalent). The fallback value is
        // the burst-nearest blend (ref r=1, moving r=0.3):
        // R (0.3 + 0.9*0.3)/1.3, G (0.5 + 0.1*0.3)/1.3, B (0.4 + 0.8*0.3)/1.3.
        val ref = sceneFrame(scene = constScene(0.3f, 0.5f, 0.4f))
        val moving = sceneFrame(
            robustness = robust { x, y -> if (x in 6..10 && y in 4..7) 0.3f else 1f },
            scene = constScene(0.9f, 0.1f, 0.8f))
        val out = RawSrBayerMerge.merge(ref, listOf(moving))
        val expected = doubleArrayOf(0.57 / 1.3, 0.53 / 1.3, 0.64 / 1.3)
        for (y in 10..13) for (x in 14..19) for (c in 0..2) {
            assertEquals("($x,$y,$c)", expected[c], out.rgb[(y * W + x) * 3 + c].toDouble(), 1e-6)
        }
        for (y in 10..13) for (x in 14..19) {
            assertTrue("fallback ($x,$y)", out.fallback[y * W + x])
        }
        assertTrue("full-support corner must not fall back", !out.fallback[0])
    }

    @Test fun rcAccumulationIsExactAndSupportIsOnePlusRc() {
        val qw = 4
        val qh = 3
        val r1 = FloatArray(qw * qh) { (it + 1) * 0.05f }
        val r2 = FloatArray(qw * qh) { if (it % 3 == 0) 0f else 0.5f }
        fun frame(r: FloatArray): RawSrBayerMerge.MergeFrame {
            val w = qw * 2
            val h = qh * 2
            return RawSrBayerMerge.MergeFrame(w, h, FloatArray(w * h) { 0.4f }, BayerPattern.RGGB, 0, 0,
                isoPrecision(qw, qh), field(qw, qh), RawSrRobustness.FrameRobustness(qw, qh, r, IntArray(r.size)))
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
    }

    @Test fun poisonedInputsStayFinite() {
        val refSamples = FloatArray(W * H) { 0.4f }
        refSamples[10] = Float.NaN
        refSamples[20] = Float.POSITIVE_INFINITY
        val ref = RawSrBayerMerge.MergeFrame(W, H, refSamples, BayerPattern.RGGB, 0, 0,
            isoPrecision(), field(), robust())
        val badPrecision = isoPrecision().values
        badPrecision[0] = Float.NaN
        badPrecision[7] = Float.POSITIVE_INFINITY
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
            RawSrKernelCovariance.MatrixField(QW, QH, badPrecision), badFlow, badRobust)
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
            val precision = RawSrKernelCovariance.precision(guide.gray, tuning)
            return RawSrBayerMerge.MergeFrame(
                unpacked.width, unpacked.height, unpacked.values,
                BayerPattern.GRBG, unpacked.sensorCropLeft, unpacked.sensorCropTop,
                precision, field(QW, QH), r)
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

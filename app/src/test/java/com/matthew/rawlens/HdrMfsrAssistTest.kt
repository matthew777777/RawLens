package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Sign-edge regression coverage for the MFSR-assisted HDR merge.
 *
 * A high-contrast edge (sign, lamp) shifted between bracket frames with a smooth
 * (here: zero) flow used to average both sides of the edge into a fringe
 * ("teeth") plus blur. The hard per-quad reject must keep the reference sharp.
 */
class HdrMfsrAssistTest {
    @Test fun tileFlowAdapterScalesQuadToRawWithoutBlending() {
        val field = RawSrAlignmentField(
            imageWidth = 4, imageHeight = 4, tileSize = 12,
            columns = 1, rows = 1,
            tiles = listOf(RawSrTileFlow(1.5f, 1.5f, 1.5f, -0.5f, 0f, true))
        )
        with(HdrMfsrAssist) {
            val flow = field.asHdrFlow()
            // Nearest tile everywhere; quad flow x2 = RAW flow, never interpolated.
            for (y in 0 until 8) for (x in 0 until 8) {
                val d = flow.displacement(x, y)
                assertEquals(3f, hdrDisplacementX(d), 0f)
                assertEquals(-1f, hdrDisplacementY(d), 0f)
            }
        }
    }

    @Test fun identicalFramesValidateToOne() {
        val cfa = cfa(16, 16, 0.4f)
        val ref = HdrMergeFrame(cfa, 4_000_000L, 100)
        val identity = HdrFlowField { _, _ -> packHdrDisplacement(0f, 0f) }
        val robust = HdrMfsrAssist.validateFlow(ref, ref, identity)
        assertEquals(8, robust.width)
        assertEquals(8, robust.height)
        // Interior quads agree; the far border cannot be bilinearly sampled and
        // safely falls back to reference-only, and the 5x5 minimum erodes two
        // quads around it (25 survivors on an 8x8 grid).
        assertEquals(25, robust.r.count { it == 1f })
        assertEquals(1f, robust.r[4 * robust.width + 4], 0f)
        assertEquals(0f, robust.r[7 * robust.width + 7], 0f)
    }

    @Test fun misalignedSignEdgeIsRejectedButFlatsAreKept() {
        // Vertical sign edge at x=8 in the reference, shifted to x=10 in moving.
        val ref = HdrMergeFrame(edgeCfa(16, 16, 8, 0.2f, 0.8f), 4_000_000L, 100)
        val moving = HdrMergeFrame(edgeCfa(16, 16, 10, 0.2f, 0.8f), 4_000_000L, 100)
        val zero = HdrFlowField { _, _ -> packHdrDisplacement(0f, 0f) }
        val robust = HdrMfsrAssist.validateFlow(ref, moving, zero)
        // Quad column 4 sees 0.8 vs 0.2: a hard conflict.
        assertEquals(0f, robust.r[4 * robust.width + 4], 0f)
        assertEquals(
            RawSrRobustness.FLAG_PHOTO_CONFLICT,
            robust.flags[4 * robust.width + 4] and RawSrRobustness.FLAG_PHOTO_CONFLICT
        )
        // Quads far from the edge agree and survive the 5x5 minimum
        // (quad 7 is the unsampleable far border, always reference-only).
        assertEquals(1f, robust.r[4 * robust.width + 0], 0f)
        assertEquals(1f, robust.r[4 * robust.width + 1], 0f)
        // The 5x5 minimum erodes two quads around the conflict.
        assertEquals(0f, robust.r[4 * robust.width + 6], 0f)
    }

    @Test fun mergeSkipsRejectedEdgeQuadsAndStaysReferenceSharp() {
        // Moderate-contrast edge: without the hard reject the soft deghost blend
        // leaves a partial fringe (disagreement ~1, smoothstep weight ~0), while a
        // gross conflict would already be fully repaired and prove nothing.
        val refCfa = edgeCfa(16, 16, 8, 0.4f, 0.6f)
        val movCfa = edgeCfa(16, 16, 10, 0.4f, 0.6f)
        val reference = HdrMergeFrame(refCfa, 4_000_000L, 100)
        val zero = HdrFlowField { _, _ -> packHdrDisplacement(0f, 0f) }
        val moving = HdrMergeFrame(
            movCfa, 4_000_000L, 100, flow = zero,
            robustness = HdrMfsrAssist.validateFlow(reference, HdrMergeFrame(movCfa, 4_000_000L, 100), zero)
        )
        val merged = HdrRawMerge.merge(listOf(reference, moving), 0)
        // Rejected edge quads merge reference-only: bit-sharp, no fringe average.
        merged.values.forEachIndexed { i, v -> assertEquals(refCfa.values[i], v, 1e-5f) }

        // Sensitivity check: without the reject the same inputs fringe at the edge.
        val fringed = HdrRawMerge.merge(
            listOf(reference, HdrMergeFrame(movCfa, 4_000_000L, 100, flow = zero)), 0)
        // x=8 is bright (0.6) in the reference but dark (0.4) in the misaligned moving frame.
        assertTrue(abs(fringed.values[4 * 16 + 8] - 0.6f) > 0.05f)
    }

    @Test fun mfsrFallbackAlignsIdenticalTexturedFramesWithZeroFlow() {
        // Quad checkerboard: gradients in both axes, so the tile Hessian is full
        // rank. (A pure vertical edge is rank-1 and correctly rejected as unreliable.)
        val cfa = checkerCfa(16, 16, 0.2f, 0.8f)
        val reference = HdrMergeFrame(cfa, 4_000_000L, 100)
        val assist = HdrMfsrAssist.alignWithMfsr(reference, HdrMergeFrame(cfa, 4_000_000L, 100))
        var maxAbs = 0f
        for (y in 0 until 16) for (x in 0 until 16) {
            val d = assist.flow.displacement(x, y)
            assertTrue(hdrDisplacementX(d).isFinite() && hdrDisplacementY(d).isFinite())
            maxAbs = maxOf(maxAbs, abs(hdrDisplacementX(d)), abs(hdrDisplacementY(d)))
        }
        assertTrue("fallback flow should be near zero, was $maxAbs", maxAbs < 1e-6f)
        // Textured interior quads align with zero residual, so they are accepted.
        assertEquals(1f, assist.robustness.r[4 * assist.robustness.width + 4], 0f)
    }

    private fun cfa(width: Int, height: Int, value: Float) = UnpackedRawCfa(
        width, height, BayerPattern.RGGB, FloatArray(width * height) { value },
        RawCrop(0, 0, width, height)
    )

    /** Vertical step edge: columns below [edgeX] are [dark], the rest [bright]. */
    private fun edgeCfa(width: Int, height: Int, edgeX: Int, dark: Float, bright: Float) =
        UnpackedRawCfa(
            width, height, BayerPattern.RGGB,
            FloatArray(width * height) { i -> if (i % width < edgeX) dark else bright },
            RawCrop(0, 0, width, height)
        )

    /**
     * Five-phase diagonal texture with nonzero central differences in both axes,
     * so the tile Hessian is full rank. (A period-2 checkerboard has exactly zero
     * ±1-tap gradients and is correctly rejected as unreliable.)
     */
    private fun checkerCfa(width: Int, height: Int, dark: Float, bright: Float) =
        UnpackedRawCfa(
            width, height, BayerPattern.RGGB,
            FloatArray(width * height) { i ->
                val qx = (i % width) / 2
                val qy = (i / width) / 2
                dark + (bright - dark) * ((qx + 3 * qy) % 5) / 4f
            },
            RawCrop(0, 0, width, height)
        )
}

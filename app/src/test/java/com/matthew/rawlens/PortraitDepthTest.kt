package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PortraitDepthTest {
    /** Mirrors gather.glsl tentWeight and composite.glsl's near-band weight. */
    private fun weight(l: PortraitDepth.Layer, d: Float): Float = if (l.kind == PortraitDepth.Kind.NEAR) {
        when {
            abs(d - l.centre) <= l.bandHalfWidth -> 1f
            d < l.centre -> ((d - l.lo) / (l.centre - l.bandHalfWidth - l.lo)).coerceIn(0f, 1f)
            else -> ((l.hi - d) / (l.hi - l.centre - l.bandHalfWidth)).coerceIn(0f, 1f)
        }
    } else if (d < l.centre) ((d - l.lo) / (l.centre - l.lo)).coerceIn(0f, 1f)
    else ((l.hi - d) / (l.hi - l.centre)).coerceIn(0f, 1f)

    @Test fun layerWeightsPartitionUnityAcrossDisparity() {
        for (focus in listOf(0.2f, 0.5f, 0.7f, 0.95f)) for (far in listOf(40f, 120f, 240f)) {
            val plan = PortraitDepth.plan(focus, far, 13)
            for (i in 0..1000) {
                val d = i / 1000f
                val sum = plan.layers.sumOf { weight(it, d).toDouble() }
                assertEquals("focus=$focus far=$far d=$d", 1.0, sum, 1e-3)
            }
        }
    }

    @Test fun gathersStayWithinTheShaderRadius() {
        val plan = PortraitDepth.plan(0.6f, 200f, 13)
        for (l in plan.layers) if (l.kind != PortraitDepth.Kind.NEAR) {
            assertTrue("r'=${l.levelRadius}", l.levelRadius <= 3f) // gather.glsl RMAX
            assertTrue(l.levelRadius > 0.5f)
        }
        assertEquals(1, plan.layers.count { it.kind == PortraitDepth.Kind.NEAR })
        assertTrue(plan.layers.size <= PortraitDepth.MAX_LAYERS)
    }

    @Test fun compositeOrderIsFarToNearThenNearToFar() {
        val plan = PortraitDepth.plan(0.5f, 120f, 13)
        for (level in 0 until plan.levels) {
            val order = PortraitDepth.compositeOrder(plan, level)
            val back = order.filter { it.kind != PortraitDepth.Kind.FRONT }.map { it.centre }
            val front = order.filter { it.kind == PortraitDepth.Kind.FRONT }.map { it.centre }
            assertEquals(back.sorted(), back)
            assertEquals(front.sortedDescending(), front)
        }
    }

    @Test fun rotationRoundTrips() {
        val n = 4
        val src = FloatArray(n * n * 3) { it.toFloat() }
        for (turns in 0..3) {
            val back = Gles31PortraitBokeh.rotateCw(Gles31PortraitBokeh.rotateCw(src, n, 3, turns), n, 3, (4 - turns) % 4)
            assertArrayEquals(src, back, 0f)
        }
        // one clockwise turn: top-left goes to top-right
        val one = Gles31PortraitBokeh.rotateCw(FloatArray(n * n) { it.toFloat() }, n, 1, 1)
        assertEquals(0f, one[n - 1])
    }

    @Test fun personIsPutOnTheFocalPlane() {
        val n = PortraitDepth.NET_SIZE
        val depth = FloatArray(n * n) { (it / n).toFloat() / n } // far at top, near at bottom
        val person = FloatArray(n * n) { i -> val x = i % n; val y = i / n; if (x in 100..155 && y in 120..250) 1f else 0f }
        val fused = PortraitDepth.fuse(depth, person)
        assertTrue(fused.personFraction > 0.05f)
        val inside = fused.disparity[200 * n + 128]
        assertEquals(fused.focus, inside, 1e-4f)
    }

    /** A colour edge in the guide snaps a soft (net-resolution) depth edge back to the picture edge. */
    @Test fun colourGuideSnapsDepthToTheColourEdge() {
        val n = PortraitDepth.NET_SIZE
        val gs = 512
        // Depth: soft ramp across x = 0.5 over ~16 net pixels. Guide: red left, blue right, hard edge at x = 0.5.
        val disparity = FloatArray(n * n) { i -> (((i % n) - n / 2) / 16f + 0.5f).coerceIn(0f, 1f) }
        val guide = FloatArray(gs * gs * 3) { k -> val x = (k / 3) % gs; val c = k % 3
            if (x < gs / 2) (if (c == 0) 0.8f else 0.1f) else (if (c == 2) 0.8f else 0.1f) }
        val coef = PortraitDepth.colorGuidedCoefficients(guide, gs, disparity, radius = 8, eps = 2e-4f)
        fun d(x: Int): Float { val i = (gs / 2 * gs + x) * 4; val g = (gs / 2 * gs + x) * 3
            return coef[i] * guide[g] + coef[i + 1] * guide[g + 1] + coef[i + 2] * guide[g + 2] + coef[i + 3] }
        val soft = PortraitDepth.resizeBilinear(disparity, n, gs)
        // Across the one-pixel colour edge the guided map steps; the soft map only moves 1/32.
        val row = gs / 2 * gs
        val guidedStep = d(gs / 2) - d(gs / 2 - 1)
        val softStep = soft[row + gs / 2] - soft[row + gs / 2 - 1]
        assertTrue("guided=$guidedStep soft=$softStep", guidedStep > 5f * softStep)
        // Away from the edge (flat colour) the filter keeps the net's depth.
        assertEquals(soft[row + 40], d(40), 0.02f)
    }

    @Test fun areaResizePreservesTheMean() {
        val src = FloatArray(1024 * 1024 * 3) { ((it * 7919) % 1000) / 1000f }
        val out = PortraitDepth.resizeArea(src, 1024, 518, 3)
        assertEquals(src.average(), out.average(), 1e-3)
    }

    @Test fun boxFilterIsAMovingAverage() {
        val s = 9
        val src = FloatArray(s * s) { if (it == 4 * s + 4) 81f else 0f }
        val out = PortraitDepth.boxFilter(src, s, 1)
        assertEquals(9f, out[4 * s + 4], 1e-4f)
        assertEquals(9f, out[3 * s + 5], 1e-4f)
        assertEquals(0f, out[2 * s + 4], 1e-4f)
    }
}

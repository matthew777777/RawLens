package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BguSliceCpuTest {

    @Test
    fun identityGridIsIdentity() {
        val grid = BguSliceCpu.identityGrid()
        assertEquals(1, grid.gw)
        assertEquals(1, grid.gh)
        assertEquals(1, grid.gz)
        for (row in 0..2) for (col in 0..3) {
            val want = if (row == col) 1f else 0f
            assertEquals(want, grid.at(0, 0, 0, row, col), 0f)
        }
    }

    @Test
    fun clampAxisMatchesGlLinearClamp() {
        // Interior: floor/ceil + frac.
        assertEquals(Triple(2, 3, 0.25f), BguSliceCpu.clampAxis(2.25f, 8))
        // At/over edges: pinned, zero frac.
        assertEquals(Triple(0, 0, 0f), BguSliceCpu.clampAxis(0f, 8))
        assertEquals(Triple(0, 0, 0f), BguSliceCpu.clampAxis(-3f, 8))
        assertEquals(Triple(7, 7, 0f), BguSliceCpu.clampAxis(7f, 8))
        assertEquals(Triple(7, 7, 0f), BguSliceCpu.clampAxis(99f, 8))
        // Single-texel dim: always texel 0.
        assertEquals(Triple(0, 0, 0f), BguSliceCpu.clampAxis(3.7f, 1))
    }

    @Test
    fun identitySliceReproducesGuideExactly() {
        // Guide (255,0,0,0) + identity grid, no dither: out must equal in.
        val guide = byteArrayOf(255.toByte(), 0, 0, 0)
        val out = BguSliceCpu.sliceScalar(
            guide, 1, 1, BguSliceCpu.identityGrid(), 1f, 1f, 1f, false
        )
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 0, 0), out)
    }

    @Test
    fun trilerpMixesZLevels() {
        // 1x1x2 grid (planar: idx = z + gz * ch): z=0 identity, z=1 twofold
        // gain. Guide mid-gray (128/255 = 0.50196); scaleZ=1 -> cellZ inside
        // (0, 1): mix ~50/50 -> developed ~= 0.50196 * 1.50196 = 0.75392
        // -> byte 192 (+-1).
        val coeffs = FloatArray(1 * 1 * 2 * 12)
        for (row in 0..2) {
            coeffs[0 + 2 * (row * 4 + row)] = 1f
            coeffs[1 + 2 * (row * 4 + row)] = 2f
        }
        val grid = BguGrid(1, 1, 2, coeffs)
        val guide = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 128.toByte())
        val out = BguSliceCpu.sliceScalar(guide, 1, 1, grid, 1f, 1f, 1f, false)
        val r = out[0].toInt() and 0xff
        val g = out[1].toInt() and 0xff
        val b = out[3].toInt() and 0xff
        assertTrue("r=$r", r in 191..193)
        assertTrue("g=$g", g in 191..193)
        assertTrue("b=$b", b in 191..193)
        // Quad layout: Gr slot mirrors G for the shader merge.
        assertEquals(out[1], out[2])
    }

    @Test
    fun greenMergeAveragesGrGb() {
        // (R,Gr,Gb,B) = (0,255,0,0): merged G = 0.5 -> identity -> 128.
        val guide = byteArrayOf(0, 255.toByte(), 0, 0)
        val out = BguSliceCpu.sliceScalar(
            guide, 1, 1, BguSliceCpu.identityGrid(), 1f, 1f, 1f, false
        )
        assertEquals(0, out[0].toInt() and 0xff)
        assertEquals(128, out[1].toInt() and 0xff)
        assertEquals(0, out[3].toInt() and 0xff)
    }

    @Test
    fun ditherStaysWithinOneLsb() {
        // Same pixels with/without dither differ by at most 1 LSB.
        val px = byteArrayOf(100.toByte(), 150.toByte(), 140.toByte(), 200.toByte())
        val guide = ByteArray(4 * 4 * 4) { px[it % 4] }
        val grid = BguSliceCpu.identityGrid()
        val plain = BguSliceCpu.sliceScalar(guide, 4, 4, grid, 4f, 4f, 4f, false)
        val dith = BguSliceCpu.sliceScalar(guide, 4, 4, grid, 4f, 4f, 4f, true)
        for (i in plain.indices) {
            val d = kotlin.math.abs((plain[i].toInt() and 0xff) - (dith[i].toInt() and 0xff))
            assertTrue("index $i diff $d", d <= 1)
        }
    }

    @Test
    fun stepTwoSubsamplesNearest() {
        // 2x2 guide, identity grid, no dither, step=2: single output pixel
        // equals guide texel (0,0) reproduced.
        val guide = byteArrayOf(
            200.toByte(), 100.toByte(), 100.toByte(), 50.toByte(),
            10.toByte(), 20.toByte(), 30.toByte(), 40.toByte(),
            1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(),
            5.toByte(), 6.toByte(), 7.toByte(), 8.toByte()
        )
        val out = BguSliceCpu.sliceScalar(
            guide, 2, 2, BguSliceCpu.identityGrid(), 1f, 1f, 1f, false, step = 2
        )
        assertEquals(4, out.size)
        assertArrayEquals(byteArrayOf(200.toByte(), 100.toByte(), 100.toByte(), 50.toByte()), out)
    }

    @Test
    fun fallbackSelectorCoversGpuBreakageOnly() {
        assertTrue(cpuFallbackWhen("spv-missing", true))
        assertTrue(cpuFallbackWhen("vulkan-init", true))
        assertTrue(cpuFallbackWhen("egl-import", true))
        // Missing lib: CPU is in the same .so, so fail over to legacy.
        assertFalse(cpuFallbackWhen("vulkan-init", false))
        assertFalse(cpuFallbackWhen("vulkan-unavailable", true))
        // Mid-session / non-GPU fatals stay fatal (legacy takes over).
        assertFalse(cpuFallbackWhen("vulkan-busy", true))
        assertFalse(cpuFallbackWhen("vulkan-5", true))
        assertFalse(cpuFallbackWhen("no-grid", true))
        assertFalse(cpuFallbackWhen("swap: egl=0x3000", true))
        assertFalse(cpuFallbackWhen("render-starve", true))
    }

    @Test
    fun ignIsDeterministicUnitNoise() {
        val a = BguSliceCpu.ign(3.5f, 9.5f, 1f)
        val b = BguSliceCpu.ign(3.5f, 9.5f, 1f)
        assertEquals(a, b, 0f)
        assertTrue(a in 0f..1f)
        // Distinct pixels/channels differ (white-ish, not constant).
        val c = BguSliceCpu.ign(4.5f, 9.5f, 1f)
        assertTrue(kotlin.math.abs(a - c) > 1e-3f)
    }
}

package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AlignV2Test {
    @Test fun binomialTaps() {
        assertArrayEquals(floatArrayOf(.25f, .5f, .25f), Resample.pyramidKernel(2, binomial = true), 0f)
        assertArrayEquals(FloatArray(7) { floatArrayOf(1f, 2f, 3f, 4f, 3f, 2f, 1f)[it] / 16f },
            Resample.pyramidKernel(4, binomial = true), 0f)
        assertEquals(15, Resample.pyramidKernel(8, binomial = true).size)
        // Default stays the sampled gaussian (17 taps at factor 4).
        assertEquals(17, Resample.pyramidKernel(4).size)
    }

    @Test fun binomialPyramidShapesMatchBuild() {
        val img = Array(100) { y -> FloatArray(120) { x -> (x * 7 + y * 3 % 11).toFloat() } }
        val factors = listOf(1, 2, 4)
        val built = Resample.buildGaussianPyramid(img, factors, binomial = true)
        assertEquals(Resample.pyramidShapes(100, 120, factors, binomial = true), built.map { it.size to it[0].size })
    }

    @Test fun medianRemovesOutlierKeepsRamp() {
        val flow = Array(5) { y -> Array(6) { x -> floatArrayOf(x.toFloat(), y * 0.5f) } }
        flow[2][3] = floatArrayOf(40f, -9f)
        val m = AlignGpu.median3x3(flow)
        assertEquals(3f, m[2][3][0], 0f); assertEquals(1f, m[2][3][1], 0f)
        assertEquals(1f, m[1][1][0], 0f)
    }

    @Test fun medianMatchesSort() {
        val rng = java.util.Random(3)
        val flow = Array(7) { Array(9) { floatArrayOf(rng.nextFloat(), rng.nextFloat()) } }
        val m = AlignGpu.median3x3(flow)
        for (y in 0 until 7) for (x in 0 until 9) for (c in 0..1) {
            val v = ArrayList<Float>()
            for (j in -1..1) for (i in -1..1) v.add(flow[(y + j).coerceIn(0, 6)][(x + i).coerceIn(0, 8)][c])
            assertEquals(v.sorted()[4], m[y][x][c], 0f)
        }
    }

    @Test fun levelsTrimForCrops() {
        val full = BurstConfig.alignV2Levels(16, 3024, 4032)
        assertEquals(listOf(1, 2, 2, 4, 4, 4), full.factors)
        assertEquals(listOf(16, 16, 16, 16, 8, 8), full.tileSizes)
        assertEquals(listOf(1, 1, 3, 3, 3, 3), full.searchRadii)
        val crop = BurstConfig.alignV2Levels(16, 512, 512)
        assertEquals(listOf(1, 2, 2, 4), crop.factors)
        val shapes = Resample.pyramidShapes(512, 512, crop.factors, binomial = true).reversed()
        for (i in shapes.indices) assert(shapes[i].first >= crop.tileSizes[i])
    }
}

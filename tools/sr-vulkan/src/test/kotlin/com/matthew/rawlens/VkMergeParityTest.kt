// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end Vulkan merge proof: the ported [VkRawSrProcessor] merges a
 * real 2-frame crop and its linear RGB must match the CPU oracle
 * ([RawSrBayerMerge]) within float-vs-double tolerance. Exercises every
 * pass (normalize, hot, pyramid, align, covariance, robustness, unblocker,
 * accumulate, finalize) through the manifest-driven host.
 */
class VkMergeParityTest {
    @Test
    fun vulkanMergeMatchesCpuOracle() {
        val here = File(System.getProperty("user.dir"))
        val dngDir = File(here, "../../captures/dcg/vulkan")
        assertTrue(dngDir.isDirectory)
        val loaded = DngFrameLoader.load(dngDir, limit = 2, crop = intArrayOf(0, 0, 256, 256))
        val packed = loaded.map { RawSrPackedFrame.fromMetadata(it.plane, it.metadata) }
        val ref = loaded.first().metadata
        assertEquals(256, packed.first().width)

        val gpu = VkRawSrProcessor(Context()).use { proc ->
            proc.processPacked(packed, noiseLut = null) { output ->
                assertEquals(256, output.width)
                assertEquals(256, output.height)
                val rgba = vkDownloadRgba32f(output.mergedTextureId)
                val rc = vkDownloadR32f(output.rcTextureId)
                assertEquals(256 * 256 * 4, rgba.size)
                GpuResult(rgba, rc, output.acceptedFrames)
            }
        }
        assertTrue("merged must be finite", gpu.rgba.all { it.isFinite() })
        assertTrue("rc must be finite", gpu.rc.all { it.isFinite() })

        val inputs = packed.mapIndexed { i, p -> RawSrMergeJob.MosaicInput(p, loaded[i].metadata) }
        val chain = RawSrMergeJob.mosaicChain(inputs, 0, noiseLut = null)
        val oracle = RawSrBayerMerge.merge(chain.reference, chain.moving)
        assertEquals(256, oracle.width)

        var sumAbs = 0.0
        var maxAbs = 0.0
        var sumOracle = 0.0
        for (i in oracle.rgb.indices) {
            val o = oracle.rgb[i].toDouble()
            val g = gpu.rgba[(i / 3) * 4 + i % 3].toDouble()
            val d = kotlin.math.abs(g - o)
            sumAbs += d
            maxAbs = maxOf(maxAbs, d)
            sumOracle += kotlin.math.abs(o)
        }
        val meanAbs = sumAbs / oracle.rgb.size
        val meanLevel = sumOracle / oracle.rgb.size
        println("vulkan-vs-cpu: meanAbs=$meanAbs maxAbs=$maxAbs meanLevel=$meanLevel " +
            "accepted=${gpu.acceptedFrames}")
        // float32 GPU vs float64 CPU with different summation order.
        // Measured 2026-09-25 (M1/MoltenVK, 256px DCG crop): meanAbs=1.0e-8,
        // maxAbs=8.9e-8 at level 0.139. Thresholds keep >100x headroom while
        // catching any real packing/dispatch regression.
        assertTrue("meanAbs $meanAbs too large for level $meanLevel", meanAbs < 1e-5)
        assertTrue("maxAbs $maxAbs too large", maxAbs < 1e-3)
        assertTrue("merge must move pixels off black", meanLevel > 1e-3)
    }

    private data class GpuResult(val rgba: FloatArray, val rc: FloatArray, val acceptedFrames: Int)
}

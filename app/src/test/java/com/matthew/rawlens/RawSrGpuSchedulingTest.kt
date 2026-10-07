// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawSrGpuSchedulingTest {
    @Test fun slicesCoverEveryPixelOnceIncludingPartialWorkgroups() {
        for ((w, h) in listOf(7 to 5, 514 to 386, 4080 to 3060)) {
            val visits = ByteArray(w * h)
            for (s in RawSrGpuScheduling.slices(w, h, 8, 8, 131072)) {
                assertTrue(s.groupsX * s.groupsY * 64 <= 131072)
                for (y in s.y until minOf(h, s.y + s.groupsY * 8))
                    for (x in s.x until minOf(w, s.x + s.groupsX * 8)) visits[y * w + x]++
            }
            assertTrue(visits.all { it == 1.toByte() })
        }
    }

    @Test fun expensiveAlignmentIsBoundedEvenWhenOneRowExceedsBudget() {
        val slices = RawSrGpuScheduling.slices(255, 192, 1, 1, 64).toList()
        assertTrue(slices.all { it.groupsX * it.groupsY <= 64 })
        assertEquals(255 * 192, slices.sumOf { it.groupsX * it.groupsY })
    }

    @Test fun productionBudgetKeepsFullResPassesUnderOneViewfinderFrame() {
        // Phone schedule: 256K-pixel slices bound every in-flight submit so
        // one SR submit never holds the shared GPU past a VF frame.
        val budget = 262144
        val slices = RawSrGpuScheduling.slices(4080, 3060, 8, 8, budget).toList()
        assertEquals(48, slices.size)
        assertTrue(slices.all { it.groupsX * it.groupsY * 64 <= budget })
        val visits = ByteArray(4080 * 3060)
        for (s in slices)
            for (y in s.y until minOf(3060, s.y + s.groupsY * 8))
                for (x in s.x until minOf(4080, s.x + s.groupsX * 8)) visits[y * 4080 + x]++
        assertTrue(visits.all { it == 1.toByte() })
    }

    @Test fun sliceBudgetsAreTieredByPassCost() {
        // 1x1-workgroup alignment pair stays fine-grained.
        assertEquals(16384, VkBound.sliceBudgetFor("rawsr/block_match.glsl"))
        assertEquals(16384, VkBound.sliceBudgetFor("rawsr/lk_refine.glsl"))
        // Neighborhood passes: 1M pixels per submit.
        for (pass in listOf("merge_accumulate", "fft_stage", "chroma_from_luma",
            "inpaint_dead_lanes", "kernel_covariance", "robustness")) {
            assertEquals(1048576, VkBound.sliceBudgetFor("rawsr/$pass.glsl"))
        }
        // Pointwise/small-kernel passes and unknown futures: 4M.
        for (pass in listOf("merge_finalize", "fft_remap", "preprocess",
            "pyramid_downsample", "clear_accumulators", "circular_pad",
            "hot_inpaint", "flow_upscale", "unblocker_weight",
            "robustness_accumulate", "bayer_quad_gray", "linear_guide",
            "flow_deflip", "flow_regularize", "hot_mask", "robustness_min",
            "unblocker_downsample", "unblocker_modulate", "clear_reference")) {
            assertEquals(4194304, VkBound.sliceBudgetFor("rawsr/$pass.glsl"))
        }
        assertEquals(4194304, VkBound.sliceBudgetFor("rawsr/some_future_pass.glsl"))
    }

    @Test fun tieredBudgetsStillCoverEveryPixelOnce() {
        // A 12MP pass at the cheap tier runs 3 slices, medium 12: every
        // pixel still dispatches exactly once (slicing only translates
        // global IDs, so the math is budget-independent).
        for (budget in listOf(4194304, 1048576)) {
            val visits = ByteArray(4080 * 3060)
            for (s in RawSrGpuScheduling.slices(4080, 3060, 8, 8, budget)) {
                assertTrue(s.groupsX * s.groupsY * 64 <= budget)
                for (y in s.y until minOf(3060, s.y + s.groupsY * 8))
                    for (x in s.x until minOf(4080, s.x + s.groupsX * 8)) visits[y * 4080 + x]++
            }
            assertTrue(visits.all { it == 1.toByte() })
        }
        assertEquals(3, RawSrGpuScheduling.slices(4080, 3060, 8, 8, 4194304).toList().size)
        assertEquals(12, RawSrGpuScheduling.slices(4080, 3060, 8, 8, 1048576).toList().size)
    }

    @Test fun rendererStallDoesNotRestartHealthyRawStream() {
        assertFalse(RawSrGpuScheduling.shouldRecoverCamera(5000, 4900))
        assertTrue(RawSrGpuScheduling.shouldRecoverCamera(5000, 2999))
        assertTrue(RawSrGpuScheduling.shouldRecoverCamera(5000, 0))
    }
}

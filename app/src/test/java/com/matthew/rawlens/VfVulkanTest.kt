// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VfVulkanTest {
    @Test fun `device loss bypasses retries and schedules recreation`() {
        val lost = VfVulkan.describe(VfVulkan.DEVICE_LOST)
        assertEquals("device-lost", lost)
        assertTrue(VfVulkan.shouldDisableImmediately(lost))
        assertTrue(VfVulkan.shouldRecover(lost))
        assertFalse(VfVulkan.shouldDisableImmediately(VfVulkan.describe(VfVulkan.SUBMIT_FAILED)))
        assertFalse(VfVulkan.shouldDisableImmediately("input-import"))
    }

    @Test fun `only device failures schedule recreation`() {
        assertTrue(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.SUBMIT_FAILED)))
        assertTrue(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.DEVICE_FAILED)))
        assertFalse(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.INPUT_IMPORT_FAILED)))
        assertFalse(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.OUTPUT_IMPORT_FAILED)))
        assertFalse(VfVulkan.shouldRecover("egl-import"))
    }

    @Test fun `param packing matches the native push-constant layout`() {
        val (iparams, fparams) = VfVulkan.packParams(
            intArrayOf(0, 1, 2, 3), 0, 0, 680, 510, 6, 4080,
            floatArrayOf(64f, 64f, 64f, 64f), 1023f
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 0, 0, 680, 510, 6, 4080), iparams)
        assertEquals(8, fparams.size)
        // Q6 fixed point bit-cast through the float slots (see VfLevels).
        val blackQ = 64 * 64
        val denQ = ((1023f - 64f) * 64 + 0.5f).toInt()
        for (i in 0..3) assertEquals(blackQ, fparams[i].toRawBits())
        for (i in 4..7) assertEquals(denQ, fparams[i].toRawBits())
    }

    @Test fun `fixed-point clamps degenerate white-minus-black`() {
        val (_, fparams) = VfVulkan.packParams(
            intArrayOf(0, 1, 2, 3), 0, 0, 1, 1, 2, 4080,
            floatArrayOf(1023f, 2000f, 0f, 0f), 1023f
        )
        assertEquals(64, fparams[4].toRawBits())
        assertEquals(64, fparams[5].toRawBits())
    }

    @Test fun `dispatch groups cover partial tiles`() {
        assertEquals(85, VfVulkan.dispatchGroups(680))
        assertEquals(64, VfVulkan.dispatchGroups(510))
        assertEquals(1, VfVulkan.dispatchGroups(1))
        assertEquals(2, VfVulkan.dispatchGroups(9))
    }

    @Test fun `result codes describe every failure stage`() {
        assertEquals("ok", VfVulkan.describe(VfVulkan.OK))
        assertEquals("input-import", VfVulkan.describe(VfVulkan.INPUT_IMPORT_FAILED))
        assertEquals("output-import", VfVulkan.describe(VfVulkan.OUTPUT_IMPORT_FAILED))
        assertEquals("submit", VfVulkan.describe(VfVulkan.SUBMIT_FAILED))
        assertEquals("image-unsupported", VfVulkan.describe(VfVulkan.IMAGE_UNSUPPORTED))
        // An incompatible HAL buffer must never schedule device recreation.
        assertFalse(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.IMAGE_UNSUPPORTED)))
    }

    @Test fun `image tier presents clean and skips busy`() {
        assertEquals(VfTierAction.PRESENT, vfImageAction(VfVulkan.OK, blackOutput = false))
        assertEquals(VfTierAction.SKIP_FRAME, vfImageAction(VfVulkan.BUSY, blackOutput = false))
    }

    @Test fun `image rejection or black output advances to bridge copy`() {
        // Import rejection (BLOB / non-R16 / unsampled) and the silent
        // black import both latch the image tier, never retry per frame.
        // The transfer-engine bridge rung is next: zero CPU copies even
        // where direct compute reads misbehave.
        assertEquals(
            VfTierAction.ADVANCE_TO_BRIDGE,
            vfImageAction(VfVulkan.IMAGE_UNSUPPORTED, blackOutput = false)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_BRIDGE,
            vfImageAction(VfVulkan.OK, blackOutput = true)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_BRIDGE,
            vfImageAction(VfVulkan.INPUT_IMPORT_FAILED, blackOutput = false)
        )
    }

    @Test fun `bridge tier presents clean and skips busy`() {
        assertEquals(VfTierAction.PRESENT, vfBridgeAction(VfVulkan.OK, blackOutput = false))
        assertEquals(VfTierAction.SKIP_FRAME, vfBridgeAction(VfVulkan.BUSY, blackOutput = false))
    }

    @Test fun `bridge failure or black output advances to plane upload`() {
        // The bridge is the last rung before CPU-touching uploads: its
        // import shares the buffer cache (same rejection), and a black
        // bridge output convicts the transfer path for the session.
        assertEquals(
            VfTierAction.ADVANCE_TO_PLANE,
            vfBridgeAction(VfVulkan.INPUT_IMPORT_FAILED, blackOutput = false)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_PLANE,
            vfBridgeAction(VfVulkan.OK, blackOutput = true)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_PLANE,
            vfBridgeAction(VfVulkan.SUBMIT_FAILED, blackOutput = false)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_PLANE,
            vfBridgeAction(VfVulkan.IMAGE_UNSUPPORTED, blackOutput = false)
        )
    }

    @Test fun `plane tier presents clean and skips busy`() {
        assertEquals(VfTierAction.PRESENT, vfPlaneAction(VfVulkan.OK, blackOutput = false))
        assertEquals(VfTierAction.SKIP_FRAME, vfPlaneAction(VfVulkan.BUSY, blackOutput = false))
    }

    @Test fun `plane failure or black output advances to lock copy`() {
        // The plane tier needs no import or lock; when it still fails (or
        // reads black) the AHB lock+memcpy tier covers, never a per-frame
        // retry of the broken upload.
        assertEquals(
            VfTierAction.ADVANCE_TO_COPY,
            vfPlaneAction(VfVulkan.COPY_UPLOAD_FAILED, blackOutput = false)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_COPY,
            vfPlaneAction(VfVulkan.OK, blackOutput = true)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_COPY,
            vfPlaneAction(VfVulkan.SUBMIT_FAILED, blackOutput = false)
        )
    }

    @Test fun `copy tier presents clean and drops to cpu on failure`() {
        assertEquals(VfTierAction.PRESENT, vfCopyAction(VfVulkan.OK, blackOutput = false))
        assertEquals(VfTierAction.SKIP_FRAME, vfCopyAction(VfVulkan.BUSY, blackOutput = false))
        assertEquals(
            VfTierAction.ADVANCE_TO_CPU,
            vfCopyAction(VfVulkan.COPY_UPLOAD_FAILED, blackOutput = false)
        )
        assertEquals(
            VfTierAction.ADVANCE_TO_CPU,
            vfCopyAction(VfVulkan.OK, blackOutput = true)
        )
    }
}

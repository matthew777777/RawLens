// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class ZslSelectedFrameCountTest {
    private val hdrOff = HdrPlusSettings()

    private fun selected(
        hdrPlus: HdrPlusSettings = hdrOff,
        configured: Int,
        format: CaptureFormat
    ): Int = ZslPolicy.zslSelectedFrameCountFor(
        hdrPlus, configured, format
    )

    private fun slots(
        hdrPlus: HdrPlusSettings = hdrOff,
        configured: Int,
        format: CaptureFormat
    ): Int = ZslPolicy.availabilitySlotCountFor(
        hdrPlus, configured, format
    )

    @Test fun hdrPlusJpegDngKeepsConfiguredCount() {
        // Logcat 2026-10-11: 12 selected in JPEG+DNG merged 6/6 — the
        // per-frame JPEG queue cap must not apply to merge-to-one HDR+.
        val hdrOn = HdrPlusSettings(enabled = true)
        assertEquals(12, selected(hdrPlus = hdrOn, configured = 12, format = CaptureFormat.JPEG_DNG))
        assertEquals(12, selected(hdrPlus = hdrOn, configured = 12, format = CaptureFormat.JPEG))
        assertEquals(12, selected(hdrPlus = hdrOn, configured = 12, format = CaptureFormat.DNG_ONLY))
    }

    @Test fun hdrPlusHonorsMergeBounds() {
        val hdrOn = HdrPlusSettings(enabled = true)
        assertEquals(30, selected(hdrPlus = hdrOn, configured = 59, format = CaptureFormat.JPEG_DNG))
        assertEquals(30, selected(hdrPlus = hdrOn, configured = 59, format = CaptureFormat.DNG_ONLY))
        assertEquals(2, selected(hdrPlus = hdrOn, configured = 1, format = CaptureFormat.DNG_ONLY))
        assertEquals(2, selected(hdrPlus = hdrOn, configured = 2, format = CaptureFormat.JPEG_DNG))
    }

    // Future SR hook: superResolutionUnchanged test here.

    @Test fun plainBurstKeepsJpegCap() {
        // Per-frame JPEG saves still serialize through the 6-slot
        // development worker; DNG-only bursts keep the full selection.
        assertEquals(6, selected(configured = 12, format = CaptureFormat.JPEG_DNG))
        assertEquals(6, selected(configured = 12, format = CaptureFormat.JPEG))
        assertEquals(12, selected(configured = 12, format = CaptureFormat.DNG_ONLY))
        assertEquals(30, selected(configured = 30, format = CaptureFormat.DNG_ONLY))
        assertEquals(4, selected(configured = 4, format = CaptureFormat.JPEG_DNG))
    }

    @Test fun availabilitySlotsCountOneForHdrPlus() {
        // The shutter gate must not grey out on large merge selections
        // in JPEG modes: merges save one artifact (one slot), so the
        // 6-slot per-frame JPEG budget never applies to them.
        val hdrOn = HdrPlusSettings(enabled = true)
        for (format in CaptureFormat.entries) {
            assertEquals(1, slots(hdrPlus = hdrOn, configured = 30, format = format))
        }
    }

    @Test fun availabilitySlotsGateFullPlainBurst() {
        assertEquals(6, slots(configured = 12, format = CaptureFormat.JPEG_DNG))
        assertEquals(6, slots(configured = 12, format = CaptureFormat.JPEG))
        assertEquals(30, slots(configured = 30, format = CaptureFormat.DNG_ONLY))
        assertEquals(4, slots(configured = 4, format = CaptureFormat.JPEG_DNG))
    }
}

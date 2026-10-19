// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JxlOutputSettingsTest {
    @Test
    fun defaultsFavorLosslessSixteenBit() {
        // Deliberate product deviations from darktable's export defaults
        // (8-bit, quality 95): a RAW-shooter app defaults to 16-bit
        // lossless. Ranges, steps, and labels still match darktable.
        val settings = JxlOutputSettings()
        assertEquals(JxlBitDepth.BIT_16, settings.bitDepth)
        assertEquals(JxlPixelType.UINT, settings.pixelType)
        assertEquals(100, settings.quality)
        assertEquals(7, settings.effort)
        assertEquals(0, settings.decodingSpeed)
        assertEquals(JxlHdrTransfer.SDR, settings.hdrTransfer)
    }

    @Test
    fun pqRequiresTenBitDepth() {
        // 8-bit PQ bands visibly: bump to 10, never drop a deeper choice.
        assertEquals(
            JxlBitDepth.BIT_10,
            JxlOutputSettings(bitDepth = JxlBitDepth.BIT_8, hdrTransfer = JxlHdrTransfer.PQ)
                .resolved().bitDepth
        )
        assertEquals(
            JxlBitDepth.BIT_8,
            JxlOutputSettings(bitDepth = JxlBitDepth.BIT_8).resolved().bitDepth
        )
        assertEquals(
            JxlBitDepth.BIT_16,
            JxlOutputSettings(bitDepth = JxlBitDepth.BIT_16, hdrTransfer = JxlHdrTransfer.PQ)
                .resolved().bitDepth
        )
    }

    @Test
    fun hdrTransferCyclesAndFallsBackToSdr() {
        assertEquals(JxlHdrTransfer.PQ, JxlHdrTransfer.SDR.next())
        assertEquals(JxlHdrTransfer.SDR, JxlHdrTransfer.PQ.next())
        assertEquals(JxlHdrTransfer.SDR, JxlHdrTransfer.fromPreference(null))
        assertEquals(JxlHdrTransfer.SDR, JxlHdrTransfer.fromPreference("NOPE"))
        assertEquals(JxlHdrTransfer.PQ, JxlHdrTransfer.fromPreference("PQ"))
    }

    @Test
    fun preferencesRoundTripHdrTransfer() {
        val pq = JxlOutputSettings.fromPreferences(
            null, null, 100, 7, 0, false, hdrTransferName = "PQ"
        )
        assertEquals(JxlHdrTransfer.PQ, pq.hdrTransfer)
        val sdr = JxlOutputSettings.fromPreferences(null, null, 100, 7, 0, false)
        assertEquals(JxlHdrTransfer.SDR, sdr.hdrTransfer)
    }

    @Test
    fun losslessOnlyOnIntegerQuality100() {
        assertTrue(JxlOutputSettings().isLossless)
        assertTrue(JxlOutputSettings(bitDepth = JxlBitDepth.BIT_8).isLossless)
        assertTrue(JxlOutputSettings(bitDepth = JxlBitDepth.BIT_12).isLossless)
        assertFalse(JxlOutputSettings(quality = 99).isLossless)
        // darktable excludes 16-bit float from SetFrameLossless (libjxl #17487).
        assertFalse(
            JxlOutputSettings(
                bitDepth = JxlBitDepth.BIT_16,
                pixelType = JxlPixelType.HALF_FLOAT
            ).isLossless
        )
        assertFalse(JxlOutputSettings(bitDepth = JxlBitDepth.FLOAT_32).isLossless)
    }

    @Test
    fun resolvedClampsRanges() {
        val resolved = JxlOutputSettings(quality = 200, effort = 0, decodingSpeed = 9).resolved()
        assertEquals(100, resolved.quality)
        assertEquals(1, resolved.effort)
        assertEquals(4, resolved.decodingSpeed)
        assertEquals(4, JxlOutputSettings(quality = 0).resolved().quality)
    }

    @Test
    fun pixelTypeOnlySurvivesOn16Bit() {
        // darktable hides the pixel-type control for every other depth.
        assertEquals(
            JxlPixelType.UINT,
            JxlOutputSettings(
                bitDepth = JxlBitDepth.BIT_8,
                pixelType = JxlPixelType.HALF_FLOAT
            ).resolved().pixelType
        )
        assertEquals(
            JxlPixelType.UINT,
            JxlOutputSettings(bitDepth = JxlBitDepth.FLOAT_32).resolved().pixelType
        )
        assertEquals(
            JxlPixelType.HALF_FLOAT,
            JxlOutputSettings(
                bitDepth = JxlBitDepth.BIT_16,
                pixelType = JxlPixelType.HALF_FLOAT
            ).resolved().pixelType
        )
    }

    @Test
    fun bitDepthCyclesAndFallsBackTo16() {
        assertEquals(JxlBitDepth.BIT_10, JxlBitDepth.BIT_8.next())
        assertEquals(JxlBitDepth.BIT_8, JxlBitDepth.FLOAT_32.next())
        assertEquals(JxlBitDepth.BIT_16, JxlBitDepth.fromPreference(null))
        assertEquals(JxlBitDepth.BIT_16, JxlBitDepth.fromPreference("UNKNOWN"))
        assertEquals(JxlBitDepth.BIT_12, JxlBitDepth.fromPreference("BIT_12"))
        assertEquals(65535, JxlBitDepth.BIT_16.maxCodeValue)
        assertEquals(255, JxlBitDepth.BIT_8.maxCodeValue)
    }

    @Test
    fun pixelTypeCyclesAndFallsBackToUint() {
        assertEquals(JxlPixelType.HALF_FLOAT, JxlPixelType.UINT.next())
        assertEquals(JxlPixelType.UINT, JxlPixelType.HALF_FLOAT.next())
        assertEquals(JxlPixelType.UINT, JxlPixelType.fromPreference(null))
    }
}

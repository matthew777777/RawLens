// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log2

/** Pins [DngExifDirectory.fields]: truthful tags only, nothing fabricated. */
class DngExifDirectoryTest {
    @Test fun returnsNullWithoutAnyFact() {
        assertNull(DngExifDirectory.fields(bare(), null))
    }

    @Test fun exposureAloneYieldsShutterVersionAndSensing() {
        val fields = ExifFields(requireNotNull(DngExifDirectory.fields(exposed(), null)))
        assertTrue(fields.has(36864))
        assertTrue(fields.has(37377))
        assertTrue(fields.has(41495))
        assertEquals(listOf(2), fields.shorts(41495))
        assertEquals(-log2(0.01), fields.signedRationals(37377).single(), 1e-6)
        for (tag in listOf(33437, 37378, 37386, 37382, 37385, 36867, 36868, 37520)) {
            assertFalse("tag $tag", fields.has(tag))
        }
    }

    @Test fun lensFactsLandAsFNumberApexFocalFlash() {
        val fields = ExifFields(requireNotNull(DngExifDirectory.fields(full(), null)))
        assertEquals(2.8, fields.rationals(33437).single(), 1e-9)
        assertEquals(2.0 * log2(2.8), fields.rationals(37378).single(), 1e-6)
        assertEquals(5.4, fields.rationals(37386).single(), 1e-9)
        assertEquals(4.55, fields.rationals(37382).single(), 1e-9)
        assertEquals(listOf(0), fields.shorts(37385))
    }

    @Test fun flashFiredEncodesOne() {
        val fields = ExifFields(requireNotNull(DngExifDirectory.fields(full(flash = true), null)))
        assertEquals(listOf(1), fields.shorts(37385))
    }

    @Test fun datesSubSecAndOffsetsFollowWallClock() {
        // Fixed instant: sub-second must read exactly; wall text and zone
        // follow the device timezone, so pin shape, not value.
        val fields = ExifFields(requireNotNull(
            DngExifDirectory.fields(bare(), 1_700_000_000_123L)))
        val stamp = fields.ascii(36867)
        assertTrue(stamp, stamp.matches(Regex("\\d{4}:\\d\\d:\\d\\d \\d\\d:\\d\\d:\\d\\d")))
        assertEquals(stamp, fields.ascii(36868))
        for (tag in listOf(37520, 37521, 37522)) assertEquals("123", fields.ascii(tag))
        for (tag in listOf(36880, 36881, 36882)) {
            assertTrue(fields.ascii(tag), fields.ascii(tag).matches(Regex("[+-]\\d\\d:\\d\\d")))
        }
    }

    @Test fun nonPositiveCaptureTimeOmitsDates() {
        val fields = ExifFields(requireNotNull(DngExifDirectory.fields(exposed(), 0L)))
        assertFalse(fields.has(36867))
        assertFalse(fields.has(37520))
    }

    @Test fun apexHelpersMatchTvAvDefinitions() {
        assertEquals(0.0, DngExifDirectory.apexShutter(1.0), 0.0)
        assertEquals(-1.0, DngExifDirectory.apexShutter(2.0), 1e-12)
        assertEquals(0.0, DngExifDirectory.apexAperture(1.0), 0.0)
        assertTrue(abs(DngExifDirectory.apexAperture(2.8) - 2.0 * log2(2.8)) < 1e-12)
    }

    @Test fun subSecPadsAndWraps() {
        assertEquals("007", DngExifDirectory.subSec(7L))
        assertEquals("000", DngExifDirectory.subSec(1_000L))
        assertEquals("999", DngExifDirectory.subSec(1_999L))
    }

    private fun bare() = metadata()

    private fun exposed() = metadata(exposureNanos = 10_000_000L)

    private fun full(flash: Boolean = false) = metadata(
        exposureNanos = 10_000_000L,
        aperture = 2.8f, focal = 5.4f, focus = 4.55f, flash = flash
    )

    private fun metadata(
        exposureNanos: Long? = null,
        aperture: Float? = null,
        focal: Float? = null,
        focus: Float? = null,
        flash: Boolean? = null
    ): RawFrameMetadata {
        val m = mock(RawFrameMetadata::class.java)
        `when`(m.cameraId).thenReturn("0")
        `when`(m.exposureTimeNanos).thenReturn(exposureNanos)
        `when`(m.aperture).thenReturn(aperture)
        `when`(m.focalLengthMm).thenReturn(focal)
        `when`(m.focusDistanceM).thenReturn(focus)
        `when`(m.flashFired).thenReturn(flash)
        return m
    }

    /** Minimal field-list reader in the [GpsLocationTest] GpsFields style. */
    private class ExifFields(fields: TiffFields) {
        private val payloads: Map<Int, ByteArray>
        init {
            val map = HashMap<Int, ByteArray>()
            val d = fields.descriptors
            require(d.size == fields.payloads.size * 3)
            fields.payloads.forEachIndexed { i, payload -> map[d[i * 3]] = payload }
            payloads = map
        }
        fun has(tag: Int) = payloads.containsKey(tag)
        private fun bytes(tag: Int): ByteArray =
            requireNotNull(payloads[tag]) { "missing EXIF tag $tag" }
        fun shorts(tag: Int): List<Int> {
            val raw = bytes(tag)
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until raw.size / 2).map { b.getShort(it * 2).toInt() and 0xffff }
        }
        fun ascii(tag: Int): String {
            val raw = bytes(tag)
            return String(raw, 0, raw.indexOf(0).takeIf { it >= 0 } ?: raw.size, Charsets.US_ASCII)
        }
        fun rationals(tag: Int): List<Double> = rationals(bytes(tag), signed = false)
        fun signedRationals(tag: Int): List<Double> = rationals(bytes(tag), signed = true)
        private fun rationals(raw: ByteArray, signed: Boolean): List<Double> {
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until raw.size / 8).map {
                val num = b.getInt(it * 8)
                val den = b.getInt(it * 8 + 4)
                (if (signed) num.toDouble() else (num.toLong() and 0xFFFFFFFFL).toDouble()) /
                    (den.toLong() and 0xFFFFFFFFL).toDouble()
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawExposureImageTest {
    private val settings = RawExposureAssistSettings(spotMeter = true, meterMode = RawExposureMeterMode.SPOT)
    private fun sample(format: Int, source: ByteBuffer, rowStride: Int, pixelStride: Int,
                       black: DoubleArray = DoubleArray(4) { 64.0 }, white: Int = 1023) =
        RawExposureAssistSampler.sampleBuffer(source, 64, 48, rowStride, pixelStride, format,
            0, black, white, 90, false, 100L, settings)
    @Test fun packedRaw10MatchesUnpackedIncludingPaddingAndNonzeroPosition() {
        val codes = intArrayOf(304, 544, 784, 1023)
        val packedStride = 64 / 4 * 5 + 9
        val packed = ByteBuffer.allocate(7 + packedStride * 48).apply { position(7) }
        val unpackedStride = 64 * 2 + 12
        val unpacked = ByteBuffer.allocate(5 + unpackedStride * 48).order(ByteOrder.nativeOrder()).apply { position(5) }
        for (y in 0 until 48) for (x in 0 until 64 step 4) {
            val p = 7 + y * packedStride + x / 4 * 5
            var low = 0
            for (i in 0..3) {
                packed.put(p + i, (codes[i] shr 2).toByte()); low = low or ((codes[i] and 3) shl (2 * i))
                unpacked.putShort(5 + y * unpackedStride + (x + i) * 2, codes[i].toShort())
            }
            packed.put(p + 4, low.toByte())
        }
        val a = sample(ImageFormat.RAW10, packed, packedStride, 0)!!
        val b = sample(ImageFormat.RAW_SENSOR, unpacked, unpackedStride, 2)!!
        assertEquals(b.spot!!.ev, a.spot!!.ev, 1e-12)
        assertEquals(b.spot.clippedChannels, a.spot.clippedChannels)
        fun average(format: Int, buffer: ByteBuffer, row: Int, pixel: Int) = RawExposureAssistSampler.sampleBuffer(
            buffer, 64, 48, row, pixel, format, 0, DoubleArray(4) { 64.0 }, 1023, 90, false, 100L,
            settings.copy(meterMode = RawExposureMeterMode.AVERAGE))!!.average!!
        assertEquals(average(ImageFormat.RAW_SENSOR, unpacked, unpackedStride, 2), average(ImageFormat.RAW10, packed, packedStride, 0))
        assertEquals(7, packed.position()); assertEquals(5, unpacked.position())
    }
    @Test fun blackAndWhiteCalibrationActuallyChangeTheReading() {
        val source = ByteBuffer.allocate(64 * 48 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until 64 * 48) source.putShort(i * 2, 256)
        val calibrated = sample(ImageFormat.RAW_SENSOR, source, 128, 2, DoubleArray(4), 1024)!!
        assertEquals(-2.0, calibrated.spot!!.ev, 0.0)
        val static = sample(ImageFormat.RAW_SENSOR, source, 128, 2)!!
        assertEquals(kotlin.math.log2(192.0 / 959.0), static.spot!!.ev, 1e-12)
    }
    @Test fun averagePackedRaw12MatchesRawSensorWithPadding() {
        val stride = 64 / 2 * 3 + 7
        val packed = ByteBuffer.allocate(9 + stride * 48).apply { position(9) }
        val raw = ByteBuffer.allocate(3 + 140 * 48).order(ByteOrder.nativeOrder()).apply { position(3) }
        for (y in 0 until 48) for (x in 0 until 64 step 2) {
            val a = 256 + x * 32
            val b = if (x < 16) 4095 else 1536 + y * 16
            val i = 9 + y * stride + x / 2 * 3
            packed.put(i, (a shr 4).toByte()); packed.put(i + 1, (b shr 4).toByte())
            packed.put(i + 2, ((a and 15) or ((b and 15) shl 4)).toByte())
            raw.putShort(3 + y * 140 + x * 2, a.toShort())
            raw.putShort(3 + y * 140 + (x + 1) * 2, b.toShort())
        }
        fun average(format: Int, bytes: ByteBuffer, row: Int, pixel: Int) = RawExposureAssistSampler.sampleBuffer(
            bytes, 64, 48, row, pixel, format, 0, DoubleArray(4) { 128.0 }, 4095, 90, false, 100L,
            settings.copy(meterMode = RawExposureMeterMode.AVERAGE))!!.average!!
        val expected = average(ImageFormat.RAW_SENSOR, raw, 140, 2)
        assertEquals(expected, average(ImageFormat.RAW12, packed, stride, 0))
        assertEquals(25.0, expected.clippedPercent, 0.0)
        assertEquals(9, packed.position()); assertEquals(3, raw.position())
    }
    @Test fun truncatedOrUnknownPackedDataRefusesTheFrame() {
        assertNull(sample(ImageFormat.RAW_SENSOR, ByteBuffer.allocate(20), 128, 2))
        assertNull(sample(44, ByteBuffer.allocate(64 * 48), 64, 0))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end cold-pool validation on the real reported capture
 * (IMG_20261006_123449_207: 4080x3060 GBRG, black 64, white 1023): develops the actual DNG strip
 * three consecutive times through the public [RawDevelopmentCoordinator.developJpeg] path on the
 * real GLES engine and asserts no tile-drop (exact-zero tile next to lit content) in any
 * developed bitmap. Frame 0 runs on a cold texture pool — the historically failing case — and
 * the per-frame timings in logcat show multi-frame stability.
 *
 * Requires the DNG pushed to the device first:
 * `adb push IMG_20261006_123449_207.dng /data/local/tmp/rawlens-e2e/`
 */
@RunWith(AndroidJUnit4::class)
class DevelopDngEndToEndInstrumentedTest {
    @Test fun reportedDngDevelopsWithNoZeroTilesAcrossConsecutiveFrames() {
        val dng = File(DNG_DIR, DNG_NAME)
        assertTrue(
            "Missing $dng; push the reported DNG first: " +
                "adb push $DNG_NAME /data/local/tmp/rawlens-e2e/",
            dng.isFile
        )
        val strip = parseUncompressedStrip(dng)
        assertEquals(4080, strip.width)
        assertEquals(3060, strip.height)
        val rawPlane = readStrip(dng, strip)
        val metadata = frameMetadata(strip.width, strip.height)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            val timings = ArrayList<Long>()
            repeat(3) { frame ->
                val startedAt = SystemClock.elapsedRealtime()
                val developed = coordinator.developJpeg(
                    rawPlane,
                    metadata,
                    settings = RawDevelopmentSettings(),
                    outputSettings = JpegOutputSettings()
                )
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                timings += elapsed
                try {
                    assertEquals(strip.width, developed.bitmap.width)
                    assertEquals(strip.height, developed.bitmap.height)
                    val drops = DevelopTileDropDetector.findZeroTiles(
                        developed.bitmap.width, developed.bitmap.height
                    ) { x, y -> developed.bitmap.getPixel(x, y) }
                    Log.i(TAG, "frame=$frame developMs=$elapsed drops=$drops")
                    assertTrue("frame=$frame dropped tiles: $drops", drops.isEmpty())
                } finally {
                    developed.bitmap.recycle()
                }
            }
            Log.i(TAG, "consecutive develop timings ms: $timings")
        } finally {
            coordinator.close()
        }
    }

    private data class DngStrip(val width: Int, val height: Int, val offset: Long, val byteCount: Int)

    private fun parseUncompressedStrip(dng: File): DngStrip {
        FileInputStream(dng).use { stream ->
            val header = ByteArray(8)
            readFully(stream, header)
            require(header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte()) {
                "Expected little-endian TIFF"
            }
            require(u16(header, 2) == 42) { "Bad TIFF magic" }
            stream.channel.position(u32(header, 4))
            val countBytes = ByteArray(2)
            readFully(stream, countBytes)
            val count = u16(countBytes, 0)
            var width = 0
            var height = 0
            var offset = 0L
            var byteCount = 0
            var compression = -1
            repeat(count) {
                val entry = ByteArray(12)
                readFully(stream, entry)
                val tag = u16(entry, 0)
                val type = u16(entry, 2)
                val value = entry.copyOfRange(8, 12)
                fun scalar(): Long = when (type) {
                    3 -> u16(value, 0).toLong()
                    4 -> u32(value, 0)
                    else -> 0L
                }
                when (tag) {
                    256 -> width = scalar().toInt()
                    257 -> height = scalar().toInt()
                    259 -> compression = scalar().toInt()
                    273 -> offset = scalar()
                    279 -> byteCount = scalar().toInt()
                }
            }
            require(compression == 1) { "Expected uncompressed DNG, got compression=$compression" }
            require(width > 0 && height > 0 && offset > 0 && byteCount == width * height * 2) {
                "Unexpected DNG strip: ${width}x$height offset=$offset bytes=$byteCount"
            }
            return DngStrip(width, height, offset, byteCount)
        }
    }

    private fun readStrip(dng: File, strip: DngStrip): ByteBuffer {
        val out = ByteBuffer.allocateDirect(strip.byteCount).order(ByteOrder.nativeOrder())
        FileInputStream(dng).use { stream ->
            stream.channel.position(strip.offset)
            val chunk = ByteArray(1 shl 20)
            var remaining = strip.byteCount
            while (remaining > 0) {
                val got = stream.read(chunk, 0, minOf(chunk.size, remaining))
                require(got > 0) { "Truncated DNG strip" }
                out.put(chunk, 0, got)
                remaining -= got
            }
        }
        out.flip()
        return out
    }

    private fun frameMetadata(width: Int, height: Int): RawFrameMetadata {
        val fullFrame = IntRectSnapshot(0, 0, width, height)
        return RawFrameMetadata(
            cameraId = "instrumented-dng",
            timestampNanos = 246421605211521L,
            frameNumber = 361L,
            imageWidth = width,
            imageHeight = height,
            imageCrop = fullFrame,
            rawPlaneCount = 1,
            rawPlaneRowStride = width * 2,
            rawPlanePixelStride = 2,
            exifOrientation = 1,
            sensorOrientationDegrees = 0,
            sensitivityIso = 50,
            exposureTimeNanos = 2197802L,
            frameDurationNanos = 33333333L,
            rollingShutterSkewNanos = null,
            cfaPattern = BayerPattern.GBRG,
            rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(floatArrayOf(64f, 64f, 64f, 64f)),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = 1023f,
            whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = width to height,
            activeArray = fullFrame,
            preCorrectionActiveArray = fullFrame,
            rawCropRegion = null,
            bufferGeometry = RawBufferGeometry.Supported(
                sensorOriginX = 0,
                sensorOriginY = 0,
                processingCrop = RawCrop(0, 0, width, height),
                provenance = "instrumented-dng"
            ),
            lensShadingAlreadyApplied = false,
            lensShadingMap = null,
            hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(0.5, 1.0, 0.67)),
            colorCorrectionTransform = null,
            colorMatrix1 = null,
            colorMatrix2 = null,
            cameraCalibration1 = null,
            cameraCalibration2 = null,
            forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
            forwardMatrix2 = null,
            referenceIlluminant1 = null,
            referenceIlluminant2 = null,
            noiseProfile = null,
            sensorPixelMode = null,
            rawBinningFactorUsed = false,
            activePhysicalCameraId = null
        )
    }

    private fun readFully(stream: FileInputStream, buffer: ByteArray) {
        var at = 0
        while (at < buffer.size) {
            val got = stream.read(buffer, at, buffer.size - at)
            require(got > 0) { "Truncated TIFF header" }
            at += got
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (u16(bytes, at) or (u16(bytes, at + 2) shl 16)).toLong() and 0xffffffffL

    private companion object {
        const val TAG = "TileDropE2E"
        const val DNG_DIR = "/data/local/tmp/rawlens-e2e"
        const val DNG_NAME = "IMG_20261006_123449_207.dng"
    }
}

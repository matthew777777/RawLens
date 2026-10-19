// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

class JxlMetadataBoxesTest {
    @Test
    fun exifBlobCarriesJpegParityTags() {
        val boxes = JxlMetadataBoxes.build(
            metadata = metadata(),
            awbAuto = true,
            captureTimeMillis = 1_700_000_000_000L,
            displayP3 = false,
            gps = GpsLocation(
                latitude = 52.2297, longitude = 21.0122, altitudeMeters = 100.0,
                timeMillis = 1_700_000_000_000L, accuracyMeters = 5f,
                processingMethod = GpsLocation.METHOD_GPS
            ),
            make = "Google",
            model = "Pixel 8"
        )
        val tiff = Tiff.root(boxes.exif)
        assertEquals("Google", tiff.ascii(0x010F))
        assertEquals("Pixel 8", tiff.ascii(0x0110))
        assertEquals("RawLens scene-referred developer", tiff.ascii(0x0131))
        assertEquals(4000, tiff.long(0x0100))
        assertEquals(3000, tiff.long(0x0101))
        assertEquals(1, tiff.short(0x0112))
        assertEquals(1, tiff.short(0xA001))
        assertTrue(tiff.ascii(0x0132).matches(Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")))
        val exif = tiff.subIfd(0x8769)
        assertEquals("0232", exif.raw(0x9000).toString(Charsets.US_ASCII))
        assertEquals(1L to 100L, exif.rational(0x829A))
        assertEquals(2800L to 1000L, exif.rational(0x829D))
        assertEquals(100, exif.short(0x8827))
        assertEquals(4300L to 1000L, exif.rational(0x920A))
        assertEquals(0, exif.short(0x9209))
        assertEquals(0, exif.short(0xA403))
        val gps = tiff.subIfd(0x8825)
        assertEquals("N", gps.ascii(0x0001))
        assertEquals("E", gps.ascii(0x0003))
        assertEquals("52/1,13/1,46920/1000", gps.exifString(0x0002))
        assertEquals(0, gps.byte(0x0005))
        assertEquals(100000L to 1000L, gps.rational(0x0006))
    }

    @Test
    fun displayP3MarksUncalibrated() {
        val boxes = JxlMetadataBoxes.build(
            metadata = metadata(), awbAuto = null, captureTimeMillis = 0L,
            displayP3 = true, gps = null, make = "M", model = "M"
        )
        assertEquals(0xFFFF, Tiff.root(boxes.exif).short(0xA001))
    }

    @Test
    fun hdrPqMarksColorSpaceUncalibrated() {
        // EXIF has no PQ code: never claim sRGB for HDR stills.
        val boxes = JxlMetadataBoxes.build(
            metadata = metadata(), awbAuto = null, captureTimeMillis = 0L,
            displayP3 = false, gps = null, make = "M", model = "M",
            hdrPq = true
        )
        assertEquals(0xFFFF, Tiff.root(boxes.exif).short(0xA001))
    }

    @Test
    fun xmpPacketParsesWithCaptureTrio() {
        val boxes = JxlMetadataBoxes.build(
            metadata = metadata(), awbAuto = false, captureTimeMillis = 0L,
            displayP3 = false, gps = null, make = "A&B", model = "M<X>"
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(boxes.xmp))
        val desc = doc.getElementsByTagName("rdf:Description").item(0)
        // Escaped values survive the XML round trip.
        assertEquals("A&B", desc.attributes.getNamedItem("tiff:Make").nodeValue)
        assertEquals("M<X>", desc.attributes.getNamedItem("tiff:Model").nodeValue)
        assertEquals("4000", desc.attributes.getNamedItem("tiff:ImageWidth").nodeValue)
        assertEquals("1/100", desc.attributes.getNamedItem("exif:ExposureTime").nodeValue)
        assertEquals("2800/1000", desc.attributes.getNamedItem("exif:FNumber").nodeValue)
        assertEquals("100", desc.attributes.getNamedItem("exif:PhotographicSensitivity").nodeValue)
        assertEquals("1", desc.attributes.getNamedItem("exif:WhiteBalance").nodeValue)
        assertEquals("image/jxl", desc.attributes.getNamedItem("dc:format").nodeValue)
    }

    /** Minimal little-endian TIFF reader for the builder's own output. */
    private class Tiff private constructor(
        private val buf: ByteBuffer,
        private val entries: Map<Int, Entry>
    ) {
        companion object {
            fun root(blob: ByteArray): Tiff {
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                require(buf.getShort(0).toInt() == 0x4949) { "Not II TIFF" }
                require(buf.getShort(2).toInt() == 42) { "Bad TIFF magic" }
                val tiff = Tiff(buf, emptyMap())
                return Tiff(buf, tiff.readIfd(buf.getInt(4)))
            }
        }

        private data class Entry(val type: Int, val count: Int, val inline: ByteArray, val offset: Int)

        private fun readIfd(at: Int): Map<Int, Entry> {
            val n = buf.getShort(at).toInt() and 0xffff
            val map = HashMap<Int, Entry>()
            for (i in 0 until n) {
                val p = at + 2 + i * 12
                val bytes = ByteArray(4)
                buf.position(p + 8)
                buf.get(bytes)
                map[buf.getShort(p).toInt() and 0xffff] = Entry(
                    type = buf.getShort(p + 2).toInt() and 0xffff,
                    count = buf.getInt(p + 4),
                    inline = bytes,
                    offset = buf.getInt(p + 8)
                )
            }
            return map
        }

        private fun unitSize(type: Int): Int = when (type) {
            1, 2, 7 -> 1
            3 -> 2
            4 -> 4
            5 -> 8
            else -> error("type $type")
        }

        private fun bytes(tag: Int): ByteArray {
            val e = entries[tag] ?: error("Missing tag ${tag.toString(16)}")
            if (unitSize(e.type) * e.count <= 4) return e.inline.copyOf(unitSize(e.type) * e.count)
            return ByteArray(unitSize(e.type) * e.count).also {
                buf.position(e.offset)
                buf.get(it)
            }
        }

        fun ascii(tag: Int): String = bytes(tag).toString(Charsets.US_ASCII).trimEnd('\u0000')
        fun short(tag: Int): Int =
            ByteBuffer.wrap(bytes(tag)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun long(tag: Int): Long =
            ByteBuffer.wrap(bytes(tag)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

        fun rational(tag: Int): Pair<Long, Long> {
            val b = ByteBuffer.wrap(bytes(tag)).order(ByteOrder.LITTLE_ENDIAN)
            return (b.int.toLong() and 0xffffffffL) to (b.int.toLong() and 0xffffffffL)
        }

        fun raw(tag: Int): ByteArray = bytes(tag)
        fun byte(tag: Int): Int = bytes(tag)[0].toInt() and 0xff
        fun exifString(tag: Int): String {
            val b = ByteBuffer.wrap(bytes(tag)).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes(tag).size / 8
            return (0 until n).joinToString(",") { "${b.int}/${b.int}" }
        }

        fun subIfd(tag: Int): Tiff = Tiff(buf, readIfd(long(tag).toInt()))
    }

    private fun metadata() = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 4000, imageHeight = 3000,
        imageCrop = IntRectSnapshot(0, 0, 4000, 3000), rawPlaneCount = 1, rawPlaneRowStride = 8000,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 4000 to 3000,
        activeArray = IntRectSnapshot(0, 0, 4000, 3000), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 8, 8), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        forwardMatrix2 = null, referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null,
        sensorPixelMode = null, rawBinningFactorUsed = false, activePhysicalCameraId = "wide",
        aperture = 2.8f, focalLengthMm = 4.3f, flashFired = false
    )
}

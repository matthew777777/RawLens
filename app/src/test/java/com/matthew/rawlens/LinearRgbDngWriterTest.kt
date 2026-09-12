package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LinearRgbDngWriterTest {
    // ---- quantization policy ----

    @Test fun quantizationScaleAndClippingAreDocumented() {
        assertEquals(65535.0, LinearRgbDngWriter.QUANTIZATION_SCALE, 0.0)
        assertEquals(0, LinearRgbDngWriter.quantize(0f))
        assertEquals(65535, LinearRgbDngWriter.quantize(1f))
        assertEquals(32768, LinearRgbDngWriter.quantize(0.5f))
        assertEquals(0, LinearRgbDngWriter.quantize(-0.25f))
        assertEquals(65535, LinearRgbDngWriter.quantize(1.5f))
    }

    @Test fun quantizationRejectsNonFinite() {
        assertThrows(IllegalArgumentException::class.java) { LinearRgbDngWriter.quantize(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            LinearRgbDngWriter.quantize(Float.POSITIVE_INFINITY)
        }
    }

    @Test fun tripletsDropAlphaAtTheBoundary() {
        val rgba = floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 0.4f, 0.5f, 0.6f, 1f)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f),
            MergedLinearRgb.toTriplets(rgba), 0f
        )
    }

    @Test fun tripletsAreIdenticalAtAnyWorkerCount() {
        // Threading contract: shards cover disjoint pixels with the serial
        // index math untouched, so the repack must agree bitwise.
        var s = 999L
        val rgba = FloatArray(4 * 1024) {
            s = (s * 1103515245 + 12345) % 2147483648
            (s % 10000) / 10000f
        }
        RawSrWorkers.overrideCount = 1
        val serial = try {
            MergedLinearRgb.toTriplets(rgba)
        } finally {
            RawSrWorkers.overrideCount = null
        }
        assertArrayEquals(serial, MergedLinearRgb.toTriplets(rgba), 0f)
    }

    @Test fun mergedInputRequiresThreeSamplesAndFiniteData() {
        assertThrows(IllegalArgumentException::class.java) {
            MergedLinearRgb(2, 1, FloatArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MergedLinearRgb(1, 1, floatArrayOf(0f, 0f, Float.NaN))
        }
    }

    // ---- provenance ----

    @Test fun provenanceEnforcesBurstArithmetic() {
        val base = provenance()
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(rejectedFrames = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(acceptedFrames = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(sourceCameraId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(outputScale = 0)
        }
    }

    @Test fun provenanceBlockCarriesEveryRequiredKey() {
        val block = LinearRgbDngWriter.provenanceBlock(provenance())
        for (key in listOf(
            "algorithm=RawLens-RawSr/4D-scale1", "selectedFrames=4", "acceptedFrames=3",
            "rejectedFrames=1", "referenceTimestampNs=123456789", "outputScale=1",
            "sourceCameraId=0", "derivation=DerivedFromRawBurst", "lensShadingApplied=true",
            "quantization=clamp[0,1]*65535 round-half-up"
        )) assertTrue("missing $key", block.contains(key))
    }

    @Test fun unknownTimestampIsNeverFabricated() {
        val block = LinearRgbDngWriter.provenanceBlock(
            provenance().copy(referenceTimestampNs = Long.MIN_VALUE)
        )
        assertTrue(block.contains("referenceTimestampNs=unknown"))
        assertFalse(block.contains("referenceTimestampNs=0;"))
    }

    // ---- file structure (parser-level) ----

    @Test fun writesLinearRawTagsAndNoCfaTags() {
        val parsed = parse(writeDefault())
        assertEquals(0x4949, parsed.u16(0))
        assertEquals(42, parsed.u16(2))
        assertEquals(8, parsed.u32(4))
        assertEquals(4, parsed.entry(256).value) // width
        assertEquals(2, parsed.entry(257).value) // height
        assertEquals(listOf(16, 16, 16), parsed.shorts(258))
        assertEquals(1, parsed.entry(259).value) // uncompressed
        assertEquals(34892, parsed.entry(262).value) // LinearRaw
        assertEquals(3, parsed.entry(277).value) // three samples
        assertEquals(1, parsed.entry(284).value) // chunky
        assertEquals(6, parsed.entry(274).value) // orientation echo
        assertEquals(2, parsed.entry(278).value) // single strip
        val stripOffset = parsed.entry(273).value
        val byteCount = parsed.entry(279).value
        assertEquals(4 * 2 * 3 * 2, byteCount)
        assertEquals(stripOffset + byteCount, parsed.bytes.size)
        assertEquals(65535, parsed.entry(50717).value) // WhiteLevel
        assertEquals(listOf(0.0, 0.0, 0.0), parsed.rationals(50714)) // BlackLevel
        for (cfaTag in listOf(33421, 33422)) assertFalse("CFA tag $cfaTag", parsed.has(cfaTag))
    }

    @Test fun pixelValuesRoundTripInRgbOrder() {
        // 4x2 field: gradient plus an over-white and a negative sample.
        val rgb = FloatArray(4 * 2 * 3) { i -> (i - 6) / 12f }
        rgb[0] = 1.5f; rgb[1] = -0.25f
        val parsed = parse(write(rgb, 4, 2))
        val stripOffset = parsed.entry(273).value
        for (i in rgb.indices) {
            val expected = LinearRgbDngWriter.quantize(rgb[i])
            val actual = parsed.u16(stripOffset + i * 2)
            assertEquals("sample $i", expected, actual)
        }
        // Chunky order: pixel (1,0) starts exactly one triplet past the origin.
        assertEquals(LinearRgbDngWriter.quantize(rgb[3]), parsed.u16(stripOffset + 3 * 2))
    }

    @Test fun colorMatricesAreWrittenInRowMajorOrder() {
        val parsed = parse(writeDefault())
        // Captured Camera2 column-major [1,4,7,-2,5,8,3,6,9] must land row-major.
        val expected = listOf(1.0, -2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        assertEquals(expected, parsed.srationals(50721))
        assertEquals(listOf(0.9, 1.0, 1.1), parsed.rationals(50728)) // AsShotNeutral
        assertEquals(21, parsed.entry(50778).value)
        assertEquals(17, parsed.entry(50779).value)
    }

    @Test fun exposureIsoAndIdentityAreCopied() {
        val parsed = parse(writeDefault())
        val (num, den) = parsed.rational(33434)
        assertEquals(10_000_000, num)
        assertEquals(1_000_000_000, den)
        assertEquals(100, parsed.entry(34855).value)
        // OEM strings are environment-dependent ("unknown" under a JVM stub);
        // pin structure and the camera-ID suffix instead of exact values.
        assertTrue(parsed.ascii(271).isNotEmpty())
        assertTrue(parsed.ascii(272).isNotEmpty())
        assertTrue(parsed.ascii(50708).contains("(0)"))
        assertTrue(parsed.ascii(270).contains("derivation=DerivedFromRawBurst"))
    }

    @Test fun noiseProfileIsEmbeddedAsDoubles() {
        val parsed = parse(writeDefault())
        assertTrue("NoiseProfile", parsed.has(51041))
        assertEquals(
            listOf(2.0e-5, 1.0e-7, 1.5e-5, 1.0e-7, 2.5e-5, 2.0e-7),
            parsed.doubles(51041)
        )
    }

    @Test fun missingOptionalMetadataOmitsItsTags() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        val image = MergedLinearRgb(2, 2, FloatArray(12) { 0.25f })
        val bytes = ByteArrayOutputStream().also {
            LinearRgbDngWriter.write(it, image, metadata, provenance())
        }.toByteArray()
        val parsed = parse(bytes)
        assertFalse(parsed.has(33434))
        assertFalse(parsed.has(34855))
        assertFalse(parsed.has(50779))
        assertFalse("NoiseProfile without a model", parsed.has(51041))
    }

    @Test fun provenanceCameraMustMatchReference() {
        val image = MergedLinearRgb(2, 2, FloatArray(12) { 0.25f })
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("1")
        `when`(metadata.exifOrientation).thenReturn(1)
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayOutputStream().also {
                LinearRgbDngWriter.write(it, image, metadata, provenance())
            }
        }
    }

    // ---- helpers ----

    private fun provenance() = MergeProvenance(
        algorithmVersion = LinearRgbDngWriter.ALGORITHM_VERSION,
        selectedFrames = 4, acceptedFrames = 3, rejectedFrames = 1,
        referenceTimestampNs = 123456789L, outputScale = 1,
        sourceCameraId = "0", lensShadingApplied = true
    )

    private fun metadata(): RawFrameMetadata {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(6)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        `when`(metadata.referenceIlluminant2).thenReturn(17)
        `when`(metadata.neutralColorPoint).thenReturn(
            ImmutableDoubleValues(doubleArrayOf(0.9, 1.0, 1.1))
        )
        val captured = ImmutableDoubleValues(
            doubleArrayOf(1.0, 4.0, 7.0, -2.0, 5.0, 8.0, 3.0, 6.0, 9.0)
        )
        `when`(metadata.colorMatrix1).thenReturn(captured)
        `when`(metadata.colorMatrix2).thenReturn(captured)
        `when`(metadata.exposureTimeNanos).thenReturn(10_000_000L)
        `when`(metadata.sensitivityIso).thenReturn(100)
        `when`(metadata.cfaPattern).thenReturn(BayerPattern.RGGB)
        `when`(metadata.noiseProfile).thenReturn(
            ImmutableDoubleValues(doubleArrayOf(2.0e-5, 1.0e-7, 1.5e-5, 1.0e-7, 2.5e-5, 2.0e-7))
        )
        return metadata
    }

    private fun write(
        rgb: FloatArray = FloatArray(4 * 2 * 3) { it / 24f },
        w: Int = 4, h: Int = 2
    ): ByteArray = ByteArrayOutputStream().also {
        LinearRgbDngWriter.write(it, MergedLinearRgb(w, h, rgb), metadata(), provenance())
    }.toByteArray()

    private fun writeDefault(): ByteArray = write()

    private class Parsed(val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data class Field(val type: Int, val count: Int, val value: Int)

        val entries: Map<Int, Field> = buildMap {
            val count = buffer.getShort(8).toInt() and 0xffff
            for (i in 0 until count) {
                val p = 10 + i * 12
                val tag = buffer.getShort(p).toInt() and 0xffff
                put(tag, Field(buffer.getShort(p + 2).toInt() and 0xffff, buffer.getInt(p + 4), buffer.getInt(p + 8)))
            }
        }

        fun has(tag: Int) = entries.containsKey(tag)
        fun entry(tag: Int) = entries.getValue(tag)
        fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff
        fun u32(offset: Int) = buffer.getInt(offset)

        private fun payload(tag: Int): Int {
            val field = entry(tag)
            val unit = when (field.type) {
                3 -> 2; 4 -> 4; 5, 10, 12 -> 8; else -> 1
            }
            return if (field.count * unit <= 4) error("inline") else field.value
        }

        fun shorts(tag: Int): List<Int> {
            val field = entry(tag)
            assertEquals(3, field.type)
            return if (field.count * 2 <= 4) {
                List(field.count) { (field.value shr (it * 16)) and 0xffff }
            } else List(field.count) { u16(payload(tag) + it * 2) }
        }

        fun ascii(tag: Int): String {
            val field = entry(tag)
            assertEquals(2, field.type)
            val bytes = ByteArray(field.count) { buffer.get(payload(tag) + it) }
            return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        }

        fun rationals(tag: Int): List<Double> {
            val field = entry(tag)
            assertEquals(5, field.type)
            return List(field.count) {
                val num = buffer.getInt(payload(tag) + it * 8)
                val den = buffer.getInt(payload(tag) + it * 8 + 4)
                num.toDouble() / den
            }
        }

        fun srationals(tag: Int): List<Double> {
            val field = entry(tag)
            assertEquals(10, field.type)
            return List(field.count) {
                val num = buffer.getInt(payload(tag) + it * 8)
                val den = buffer.getInt(payload(tag) + it * 8 + 4)
                num.toDouble() / den
            }
        }

        fun doubles(tag: Int): List<Double> {
            val field = entry(tag)
            assertEquals(12, field.type)
            return List(field.count) { buffer.getDouble(payload(tag) + it * 8) }
        }

        fun rational(tag: Int): Pair<Int, Int> {
            val field = entry(tag)
            assertEquals(5, field.type)
            assertEquals(1, field.count)
            return buffer.getInt(payload(tag)) to buffer.getInt(payload(tag) + 4)
        }
    }

    private fun parse(bytes: ByteArray) = Parsed(bytes)
}

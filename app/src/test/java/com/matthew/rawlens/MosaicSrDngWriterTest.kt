package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MosaicSrDngWriterTest {
    @Test fun writesCfaTagsAndNoRgbConfusion() {
        val parsed = parse(writeDefault())
        assertEquals(12, parsed.entry(256).value)
        assertEquals(8, parsed.entry(257).value)
        assertEquals(listOf(16), parsed.shorts(258))
        assertEquals(1, parsed.entry(259).value)
        assertEquals(32803, parsed.entry(262).value) // CFA, not LinearRaw
        assertEquals(1, parsed.entry(277).value) // one sample per site
        assertEquals(1, parsed.entry(284).value)
        assertEquals(listOf(2, 2), parsed.shorts(33421))
        assertEquals(6, parsed.entry(274).value)
        val stripOffset = parsed.entry(273).value
        val byteCount = parsed.entry(279).value
        assertEquals(12 * 8 * 2, byteCount)
        assertEquals(stripOffset + byteCount, parsed.bytes.size)
        assertEquals(65535, parsed.entry(50717).value)
        // BlackLevel covers the 2x2 repeat grid (one level per Bayer phase);
        // count 1 is rejected by rawspeed/Darktable.
        assertEquals(listOf(0, 0, 0, 0), parsed.shorts(50714))
        assertEquals(listOf(0, 0, 8, 12), parsed.shorts(50829)) // target active area
    }

    @Test fun cfaPatternBytesMatchEveryBayerPhase() {
        val expected = mapOf(
            BayerPattern.RGGB to listOf(0, 1, 1, 2),
            BayerPattern.BGGR to listOf(2, 1, 1, 0),
            BayerPattern.GRBG to listOf(1, 0, 2, 1),
            BayerPattern.GBRG to listOf(1, 2, 0, 1)
        )
        for ((pattern, bytes) in expected) {
            val parsed = parse(write(pattern = pattern))
            assertEquals("$pattern", bytes, parsed.bytes33422())
        }
    }

    @Test fun pixelValuesRoundTripWithDocumentedClipping() {
        val samples = FloatArray(12 * 8) { i -> (i - 24) / 24f }
        samples[0] = 1.5f; samples[1] = -0.25f
        val parsed = parse(write(samples = samples))
        val stripOffset = parsed.entry(273).value
        for (i in samples.indices) {
            assertEquals("sample $i", LinearRgbDngWriter.quantize(samples[i]), parsed.u16(stripOffset + i * 2))
        }
        assertEquals(65535, parsed.u16(stripOffset))
        assertEquals(0, parsed.u16(stripOffset + 2))
    }

    @Test fun mosaicInputIsSingleSampleByConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrCfa(4, 4, BayerPattern.RGGB, FloatArray(4 * 4 * 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrCfa(2, 2, BayerPattern.RGGB, FloatArray(4) { Float.NaN })
        }
    }

    @Test fun provenanceRecordCarriesEffectiveFrames() {
        assertTrue(MosaicSrDngWriter.provenanceBlock(provenance(), MosaicSrCfa(12, 8, BayerPattern.GRBG,
            FloatArray(12 * 8) { 0.1f })).contains("effectiveFrames=1.0"))
    }

    @Test fun noiseProfileOverrideWritesMergedTag() {
        // metadata() carries no model here, so the tag exists if and only if
        // the override lands — proving the merged model reaches the file.
        val override = doubleArrayOf(1e-5, 5e-8, 7.5e-6, 5e-8, 1.25e-5, 1e-7)
        val bytes = ByteArrayOutputStream().also {
            MosaicSrDngWriter.write(
                it, MosaicSrCfa(12, 8, BayerPattern.GRBG, FloatArray(12 * 8) { v -> v / 96f }),
                metadata(), provenance(), noiseProfileOverride = override)
        }.toByteArray()
        assertEquals(override.toList(), parse(bytes).doubles(51041))
    }

    @Test fun provenanceRecordCarriesEveryRequiredKey() {
        val parsed = parse(writeDefault())
        val block = parsed.ascii(270)
        assertTrue(block.startsWith("RawLens Mosaic SR DNG - derived Bayer reconstruction."))
        for (key in listOf(
            "algorithm=${MosaicSrReconstructor.ALGORITHM_VERSION}", "selectedFrames=4", "acceptedFrames=3",
            "rejectedFrames=1", "referenceTimestampNs=555", "sourceDims=8x6",
            "targetDims=12x8", "targetPattern=GRBG", "outputScale=1.4142135623730951",
            "sourceCameraId=0", "derivation=MosaicSrDerivedBayer", "lensShadingApplied=false",
            "quantization=clamp[0,1]*65535 round-half-up"
        )) assertTrue("missing $key", block.contains(key))
    }

    @Test fun provenanceEnforcesBurstArithmetic() {
        val base = MosaicSrProvenance(
            selectedFrames = 4, acceptedFrames = 3, rejectedFrames = 1,
            referenceTimestampNs = 1L, sourceWidth = 8, sourceHeight = 6,
            sourceCameraId = "0", lensShadingApplied = false
        )
        assertThrows(IllegalArgumentException::class.java) { base.copy(rejectedFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(acceptedFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(sourceCameraId = "") }
    }

    @Test fun colourMetadataAndIdentityAreCopied() {
        val parsed = parse(writeDefault())
        assertEquals(listOf(1.0, -2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), parsed.srationals(50721))
        assertEquals(listOf(0.9, 1.0, 1.1), parsed.rationals(50728))
        assertEquals(21, parsed.entry(50778).value)
        assertEquals(17, parsed.entry(50779).value)
        val (num, den) = parsed.rational(33434)
        assertEquals(10_000_000, num)
        assertEquals(1_000_000_000, den)
        assertEquals(100, parsed.entry(34855).value)
        assertTrue(parsed.ascii(50708).contains("(0)"))
    }

    @Test fun provenanceCameraMustMatchReference() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("1")
        `when`(metadata.exifOrientation).thenReturn(1)
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayOutputStream().also {
                MosaicSrDngWriter.write(
                    it, MosaicSrCfa(4, 4, BayerPattern.RGGB, FloatArray(16) { 0.1f }),
                    metadata, provenance()
                )
            }
        }
    }

    // ---- helpers ----

    private fun provenance() = MosaicSrProvenance(
        selectedFrames = 4, acceptedFrames = 3, rejectedFrames = 1,
        referenceTimestampNs = 555L, sourceWidth = 8, sourceHeight = 6,
        sourceCameraId = "0", lensShadingApplied = false
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
        return metadata
    }

    private fun write(
        pattern: BayerPattern = BayerPattern.GRBG,
        samples: FloatArray = FloatArray(12 * 8) { it / 96f }
    ): ByteArray = ByteArrayOutputStream().also {
        MosaicSrDngWriter.write(it, MosaicSrCfa(12, 8, pattern, samples), metadata(), provenance())
    }.toByteArray()

    private fun writeDefault(): ByteArray = write()

    private class Parsed(val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data class Field(val type: Int, val count: Int, val value: Int)

        val entries: Map<Int, Field> = buildMap {
            val count = buffer.getShort(8).toInt() and 0xffff
            for (i in 0 until count) {
                val p = 10 + i * 12
                put(
                    buffer.getShort(p).toInt() and 0xffff,
                    Field(buffer.getShort(p + 2).toInt() and 0xffff, buffer.getInt(p + 4), buffer.getInt(p + 8))
                )
            }
        }

        fun entry(tag: Int) = entries.getValue(tag)
        fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff

        private fun dataOffset(tag: Int, unit: Int, expectType: Int): Int {
            val field = entry(tag)
            assertEquals(expectType, field.type)
            return if (field.count * unit <= 4) error("inline") else field.value
        }

        fun shorts(tag: Int): List<Int> {
            val field = entry(tag)
            assertEquals(3, field.type)
            return if (field.count * 2 <= 4) {
                List(field.count) { (field.value shr (it * 16)) and 0xffff }
            } else List(field.count) { u16(field.value + it * 2) }
        }

        fun bytes33422(): List<Int> {
            val field = entry(33422)
            assertEquals(1, field.type)
            assertEquals(4, field.count)
            return List(4) { (field.value shr (it * 8)) and 0xff }
        }

        fun ascii(tag: Int): String {
            val field = entry(tag)
            assertEquals(2, field.type)
            val bytes = ByteArray(field.count) { buffer.get(dataOffset(tag, 1, 2) + it) }
            return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        }

        fun rationals(tag: Int): List<Double> {
            val off = dataOffset(tag, 8, 5)
            val field = entry(tag)
            return List(field.count) {
                buffer.getInt(off + it * 8).toDouble() / buffer.getInt(off + it * 8 + 4)
            }
        }

        fun doubles(tag: Int): List<Double> {
            val off = dataOffset(tag, 8, 12)
            val field = entry(tag)
            return List(field.count) { buffer.getDouble(off + it * 8) }
        }

        fun srationals(tag: Int): List<Double> {
            val off = dataOffset(tag, 8, 10)
            val field = entry(tag)
            return List(field.count) {
                buffer.getInt(off + it * 8).toDouble() / buffer.getInt(off + it * 8 + 4)
            }
        }

        fun rational(tag: Int): Pair<Int, Int> {
            val off = dataOffset(tag, 8, 5)
            assertEquals(1, entry(tag).count)
            return buffer.getInt(off) to buffer.getInt(off + 4)
        }
    }

    private fun parse(bytes: ByteArray) = Parsed(bytes)
}

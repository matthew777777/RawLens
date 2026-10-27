// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class OpposedHighlightsTest {
    private fun cfa(w: Int = 48, h: Int = 48, values: FloatArray = FloatArray(w*h) { 0.5f },
                    pattern: BayerPattern = BayerPattern.RGGB) =
        UnpackedRawCfa(w,h,pattern,values,RawCrop(0,0,w,h))

    @Test fun unclippedSamplesAreBitPreservedIncludingNegatives() {
        val input = cfa(values = FloatArray(48*48) { if (it % 3 == 0) -0.01f else 0.986f })
        assertArrayEquals(input.values.map { it.toRawBits() }.toIntArray(),
            OpposedHighlights.cpu(input).values.map { it.toRawBits() }.toIntArray())
    }
    @Test fun clippedChannelUsesOpposedChannelsAndNeverReducesMeasuredSignal() {
        for (pattern in BayerPattern.entries) {
            val input = cfa(pattern = pattern)
            for (y in 0 until 48) for (x in 0 until 48) input.values[y*48+x] =
                if (pattern.colorAt(x,y) == CfaColor.RED) 1f else 4f
            val output = OpposedHighlights.cpu(input)
            assertEquals(4f, output.values[24*48+24], 0.000002f)
            for (i in input.values.indices) assertTrue(output.values[i] >= input.values[i])
        }
    }
    @Test fun cropPatternIsAlreadyShifted() {
        val input = cfa(pattern = BayerPattern.GRBG).copy(sensorCropLeft = 1, sensorCropTop = 1)
        assertArrayEquals(intArrayOf(1,0,2,1), OpposedHighlights.cells(input))
    }
    @Test fun correctionRequiresStrictlyMoreThan100Candidates() {
        val rows = FloatArray(16)
        rows[0] = 20f; rows[4] = 100f
        assertEquals(0f, OpposedHighlights.reduce(rows,2)[0],0f)
        rows[4] = 101f
        assertEquals(20f/101f,OpposedHighlights.reduce(rows,2)[0],0f)
    }
    @Test fun partialMaskTilesAndSmallImagesAreDefined() {
        for ((w,h) in listOf(4 to 4, 8 to 10, 50 to 46)) {
            val input = cfa(w,h,FloatArray(w*h) { if(it % 5 == 0) 1f else 0.7f })
            val output = OpposedHighlights.cpu(input)
            assertEquals(w*h,output.values.size)
            assertTrue(output.values.all { it.isFinite() })
        }
    }
    @Test fun shadingCannotTurnValidPhotositesIntoClippedPhotosites() {
        val input=cfa(values=FloatArray(48*48) { 1.5f })
        val shading=LensShadingModel(2,2,FloatArray(16) { 2f },IntRectSnapshot(0,0,48,48))
        assertArrayEquals(input.values,OpposedHighlights.cpu(input,shading).values,0f)
    }

    @Test fun matchesFixtureGeneratedByPinnedUpstreamOpenClKernelBodies() {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/opposed-opencl-reference.bin")).use { it.readBytes() }
        val floats=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val w=192; val h=144
        val input=FloatArray(w*h); floats.get(input)
        val expected=FloatArray(w*h); floats.get(expected)
        val rows=FloatArray(8*h); floats.get(rows)
        val chroma=FloatArray(3); floats.get(chroma)
        val frame=cfa(w,h,input)
        val analysis=OpposedHighlights.analyze(frame)
        assertArrayEquals(chroma,analysis.chroma,0.000001f)
        for(y in 0 until h) for(c in 0..2) {
            assertEquals(rows[y*8+c],analysis.rows[y*8+c],0.0001f)
            assertEquals(rows[y*8+4+c],analysis.rows[y*8+4+c],0f)
        }
        val actual=OpposedHighlights.cpu(frame).values
        println("Upstream oracle output unequalBits=" + expected.indices.count { expected[it].toRawBits()!=actual[it].toRawBits() } +
            " maxError=" + expected.indices.maxOf { kotlin.math.abs(expected[it]-actual[it]) })
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(),actual.map { it.toRawBits() }.toIntArray())
    }

}

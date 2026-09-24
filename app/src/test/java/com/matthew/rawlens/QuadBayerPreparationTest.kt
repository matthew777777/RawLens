// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class QuadBayerPreparationTest {
    @Test fun `full resolution normalization and lens channels follow grouped CFA at every crop phase`() {
        for (pattern in BayerPattern.entries) for (oy in 0..3) for (ox in 0..3) {
            val width = 12; val height = 10; val stride = 32
            val bytes = ByteBuffer.allocate(stride * height + 6).order(ByteOrder.LITTLE_ENDIAN)
            bytes.position(6)
            val black = listOf(10f, 20f, 30f, 40f)
            for (y in 0 until height) for (x in 0 until width) {
                val level = black[((y and 1) shl 1) or (x and 1)]
                bytes.putShort(6 + y * stride + x * 2, (level + (1000f-level)*0.5f).toInt().toShort())
            }
            val lens = LensShadingModel(1, 1, floatArrayOf(2f, 3f, 5f, 7f), IntRectSnapshot(0,0,12,10))
            val frame = QuadBayerPreparation.unpack(bytes, RawPlaneLayout(width,height,stride,2),
                RawNormalization(pattern,black,1000f), RawCrop(ox,oy,8,6),lens,ByteOrder.LITTLE_ENDIAN)
            assertEquals(8,frame.samples.width); assertEquals(6,frame.samples.height)
            assertEquals(48,frame.samples.values.size); assertEquals(6,bytes.position())
            for (y in 0 until 6) for (x in 0 until 8) {
                val sx = ox+x; val sy = oy+y
                val expected = when (pattern.colorAt(sx/2,sy/2)) {
                    CfaColor.RED -> 1f
                    CfaColor.BLUE -> 3.5f
                    CfaColor.GREEN -> if ((sy/2) and 1 == 0) 1.5f else 2.5f
                }
                assertEquals("$pattern origin=$ox,$oy pixel=$x,$y",expected,frame.samples.values[y*8+x],1e-6f)
            }
        }
    }

    @Test fun `quad defect repair never borrows another color group`() {
        val w=16
        val values=FloatArray(w*w) { i -> when(BayerPattern.RGGB.colorAt((i%w)/2,(i/w)/2)) {
            CfaColor.RED -> 0.2f; CfaColor.GREEN -> 0.4f; CfaColor.BLUE -> 0.6f
        } }
        values[8*w+8]=9f
        val cfa=UnpackedRawCfa(w,w,BayerPattern.RGGB,values,RawCrop(0,0,w,w))
        val stats=RawDefectCorrector.correctInPlace(cfa,listOf(IntPointSnapshot(8,8)),sameColorPeriod=4)
        assertEquals(1,stats.metadataDefectsCorrected)
        assertEquals(0.2f,values[8*w+8],1e-6f)
    }

    @Test(expected=IllegalArgumentException::class)
    fun `truncated input is rejected before processing`() {
        QuadBayerPreparation.unpack(ByteBuffer.allocate(12),RawPlaneLayout(8,8,16,2),
            RawNormalization(BayerPattern.RGGB,listOf(0f,0f,0f,0f),1023f),RawCrop(0,0,8,8),null)
    }
}

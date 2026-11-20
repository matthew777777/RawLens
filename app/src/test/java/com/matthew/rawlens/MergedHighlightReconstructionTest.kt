// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class MergedHighlightReconstructionTest {
    private fun frame(v: Float, w: Int = 48, h: Int = 48) =
        UnpackedRawCfa(w,h,BayerPattern.RGGB,FloatArray(w*h) { v },RawCrop(0,0,w,h))

    @Test fun opposedIsOnByDefaultAndLegacyOffIsMigratedOnlyOnce() {
        assertTrue(JpegOutputSettings().hlReconstruction)
        assertTrue(HighlightReconstructionDefaults.enabled(null,false))
        assertTrue(HighlightReconstructionDefaults.enabled(false,false))
        assertFalse(HighlightReconstructionDefaults.enabled(false,true))
        assertTrue(HighlightReconstructionDefaults.enabled(null,true))
    }
    @Test fun packedMaskDoesNotAliasPartialRows() {
        val mask=CfaHighlightClipping.from(34,4) { it==33 || it==34 || it==135 }
        for(i in 0 until 136) assertEquals(i==33 || i==34 || i==135,mask.isClipped(i))
    }
    @Test fun validHdrRadianceAndNearWhiteMeasurementsAreBitPreserved() {
        val cfa=frame(4f)
        val lost=24*cfa.width+24
        cfa.values[lost]=1f
        cfa.values[lost+1]=0.99f
        val masked=cfa.copy(highlightClipping=CfaHighlightClipping.from(cfa.width,cfa.height) { it==lost })
        val result=OpposedHighlights.cpu(masked)
        assertTrue(result.values[lost]>1f)
        for(i in cfa.values.indices) if(i!=lost)
            assertEquals(cfa.values[i].toRawBits(),result.values[i].toRawBits())
    }
    @Test fun hdrMaskRemainsAuthoritativeAfterDenoiseMovesAClippedSample() {
        val cfa=frame(4f)
        val lost=24*cfa.width+24
        cfa.values[lost]=0.95f
        val masked=cfa.copy(highlightClipping=CfaHighlightClipping.from(cfa.width,cfa.height) { it==lost })
        assertTrue(OpposedHighlights.cpu(masked).values[lost]>1f)
    }
    @Test fun anEmptyHdrMaskProtectsAllRadiance() {
        val cfa=frame(8f).copy(highlightClipping=CfaHighlightClipping.from(48,48) { false })
        assertArrayEquals(cfa.values,OpposedHighlights.cpu(cfa).values,0f)
    }

}

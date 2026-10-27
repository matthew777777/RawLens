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
    @Test fun bracketMergeMarksOnlyUnrecoveredClipping() {
        val full=HdrMergeFrame(frame(1f),10_000_000L,100)
        val clipped=HdrRawMerge.merge(listOf(full,full),0,HdrRawMerge.Options(deghost=false))
        assertTrue(requireNotNull(clipped.highlightClipping).any)
        val short=HdrMergeFrame(frame(0.25f),2_500_000L,100)
        val rescued=HdrRawMerge.merge(listOf(full,short),0,HdrRawMerge.Options(deghost=false))
        assertFalse(requireNotNull(rescued.highlightClipping).any)
        assertTrue(rescued.values.all { it<1f })
        // Metadata persists through the same copy discipline used by shading/denoise.
        assertSame(clipped.highlightClipping,clipped.copy(values=clipped.values.copyOf()).highlightClipping)
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
    @Test fun channelClippingIsTaggedEvenWhenOtherBayerChannelsAreNotClipped() {
        val input=frame(0.3f)
        for(y in 0 until input.height) for(x in 0 until input.width)
            if(input.pattern.colorAt(x,y)==CfaColor.RED) input.values[y*input.width+x]=1f
        val source=HdrMergeFrame(input,10_000_000L,100)
        val result=HdrRawMerge.merge(listOf(source,source),0,HdrRawMerge.Options(deghost=false))
        val mask=requireNotNull(result.highlightClipping)
        assertTrue(mask.isClipped(24*48+24))
        assertFalse(mask.isClipped(24*48+25))
    }

    @Test fun sequentialHdrMaskProtectsShortExposureRadianceAboveOne() {
        fun scene(short: Boolean): HdrMergeFrame {
            val random=java.util.Random(42)
            val cfa=UnpackedRawCfa(32,32,BayerPattern.RGGB,FloatArray(1024) { i ->
                val bright=i%32 in 12..19 && i/32 in 12..19
                val background=0.1f+random.nextFloat()*0.1f
                if(short) { if(bright) 0.4f else background*0.25f } else { if(bright) 1f else background }
            },RawCrop(0,0,32,32))
            return HdrMergeFrame(cfa,if(short) 2_500_000L else 10_000_000L,100)
        }
        val result=HdrSeqMerge.mergeTrio(listOf(scene(false),scene(true),scene(false))).cfa
        assertEquals(1.6f,result.values[15*32+15],0.001f)
        assertFalse(requireNotNull(result.highlightClipping).isClipped(15*32+15))
        assertArrayEquals(result.values,OpposedHighlights.cpu(result).values,0f)
    }

}

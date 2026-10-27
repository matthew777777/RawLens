// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class DirectLogAcesTest {
    @Test fun independentPublishedFitAnchorsUseDisplayGamma() {
        // Double-precision anchors evaluated separately from BakingLab ACES.hlsl.
        val samples = listOf(
            floatArrayOf(.18f,.18f,.18f) to floatArrayOf(.39190274f,.39190274f,.39190111f),
            floatArrayOf(1f,1f,1f) to floatArrayOf(.81891378f,.81891378f,.81891036f),
            floatArrayOf(4f,4f,4f) to floatArrayOf(.96103165f,.96103165f,.96102765f),
            floatArrayOf(1f,0f,0f) to floatArrayOf(.85572821f,0f,.08425588f),
            floatArrayOf(0f,1f,0f) to floatArrayOf(.38568199f,.82141253f,.22822052f),
            floatArrayOf(0f,0f,1f) to floatArrayOf(0f,0f,.80926862f),
            floatArrayOf(.6f,.3f,.2f) to floatArrayOf(.70840713f,.53187108f,.43852358f))
        for ((input, expected) in samples)
            assertArrayEquals(input.toList().toString(),expected,VfLogGrade.encodePixelAces(input),2e-6f)
    }

    @Test fun grayWedgeIsNeutralMonotonicAndRollsOffPastOne() {
        var previous = -1f
        for (i in 0..240) {
            val x = if(i==0) 0f else 2.0.pow(-12.0+i/12.0).toFloat()
            val rgb = VfLogGrade.encodePixelAces(floatArrayOf(x,x,x))
            assertTrue("decreasing at $x",rgb[0] >= previous)
            assertEquals(rgb[0],rgb[1],1e-5f)
            assertEquals(rgb[0],rgb[2],1e-5f)
            previous=rgb[0]
        }
        assertTrue(VfLogGrade.encodePixelAces(floatArrayOf(4f,4f,4f))[0] >
            VfLogGrade.encodePixelAces(floatArrayOf(1f,1f,1f))[0])
    }

    @Test fun saturatedAndNegativeExtendedRangeInputsStayFiniteAndLegal() {
        val values=floatArrayOf(-4f,-.1f,0f,.001f,.18f,1f,4f,16f,1024f,65504f)
        for(r in values) for(g in values) for(b in values) {
            val rgb=VfLogGrade.encodePixelAces(floatArrayOf(r,g,b))
            assertTrue(rgb.all { it.isFinite() && it in 0f..1f })
            val yuv=VfRecordPreview.rgbToYuv709Limited(rgb)
            assertTrue(yuv[0] in 64f..940f)
            assertTrue(yuv[1] in 64f..960f && yuv[2] in 64f..960f)
        }
    }

    @Test fun quantizedVideoRoundTripPreservesFittedSkinAndPrimaries() {
        for (rgb in listOf(floatArrayOf(.6f,.3f,.2f),floatArrayOf(1f,0f,0f),
            floatArrayOf(0f,1f,0f),floatArrayOf(0f,0f,1f))) {
            val encoded=VfLogGrade.encodePixelAces(rgb)
            val packed=VfRecordPreview.rgbToYuv709Limited(encoded).map { kotlin.math.round(it) }
            val decoded=VfRecordPreview.yuvToRgb709(packed[0],packed[1],packed[2])
            assertArrayEquals(encoded,decoded,.002f)
        }
    }
}

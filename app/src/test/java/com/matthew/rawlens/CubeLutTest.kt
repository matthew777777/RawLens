// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class CubeLutTest {
    private fun cube(size: Int = 16, header: String = "", row: (Float,Float,Float) -> String = { r,g,b -> "$r $g $b" }): String =
        buildString {
            append("TITLE \"Test\"\nLUT_3D_SIZE $size\n$header\n")
            repeat(size) { b -> repeat(size) { g -> repeat(size) { r ->
                append(row(r.toFloat()/(size-1),g.toFloat()/(size-1),b.toFloat()/(size-1))).append('\n')
            } } }
        }

    private fun parse(text: String) = CubeLut.parse(StringReader(text))
    private fun rejects(text: String) {
        try { parse(text); fail("Accepted malformed cube") } catch (_: IllegalArgumentException) { }
    }

    @Test fun identityLatticeAndInteriorUseRedFastestOrder() {
        val lut = parse(cube())
        for (rgb in listOf(floatArrayOf(0f,0f,0f),floatArrayOf(1f,1f,1f),
            floatArrayOf(.13f,.46f,.79f),floatArrayOf(1f,0f,.5f))) {
            assertArrayEquals(rgb,lut.apply(rgb),2e-7f)
        }
        assertEquals("Test",lut.title)
    }

    @Test fun domainRemappingAndEdgeClamping() {
        val lut = parse(cube(header="DOMAIN_MIN -1 0 2\nDOMAIN_MAX 1 2 4"))
        assertArrayEquals(floatArrayOf(.5f,.25f,.75f),lut.apply(floatArrayOf(0f,.5f,3.5f)),2e-7f)
        assertArrayEquals(floatArrayOf(0f,1f,0f),lut.apply(floatArrayOf(-5f,9f,-2f)),2e-7f)
    }

    @Test fun signedOutputsAndIntensityRetainFloatValues() {
        val lut = parse(cube { r,g,b -> "${2*r-1} ${g+1} ${3*b}" })
        val rgb = floatArrayOf(.1f,.2f,.3f)
        assertArrayEquals(floatArrayOf(-.8f,1.2f,.9f),lut.apply(rgb),2e-7f)
        assertArrayEquals(rgb,lut.apply(rgb,0f),0f)
        assertArrayEquals(floatArrayOf(-.35f,.7f,.6f),lut.apply(rgb,.5f),2e-7f)
    }

    @Test fun rejectsMalformedOrUnsupportedFiles() {
        rejects("LUT_3D_SIZE 15\n")
        rejects("LUT_3D_SIZE 66\n")
        rejects("LUT_1D_SIZE 16\n")
        rejects("LUT_3D_SIZE 16\n0 0 0\n")
        rejects(cube()+"0 0 0\n")
        rejects(cube(header="DOMAIN_MIN 1 0 0"))
        rejects(cube(header="DOMAIN_MAX NaN 1 1"))
        rejects(cube(header="DOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0"))
        rejects(cube { _,_,_ -> "NaN 0 0" })
        rejects(cube { _,_,_ -> "Infinity 0 0" })
        rejects(cube()+"DOMAIN_MIN 0 0 0\n")
    }

    @Test fun commentsBomAndWhitespace() {
        val text = "\uFEFF# test\n\n"+cube().replace("LUT_3D_SIZE 16", "LUT_3D_SIZE\t16 # comment")
        assertEquals(16,parse(text).size)
    }

    @Test fun titleCanContainHashAndTrailingComment() {
        val text = cube().replace("TITLE \"Test\"", "TITLE \"Look #1\" # description")
        assertEquals("Look #1",parse(text).title)
    }

    @Test fun largestSupportedLattice() {
        val lut = parse(cube(65))
        assertArrayEquals(floatArrayOf(.23f,.47f,.81f),lut.apply(floatArrayOf(.23f,.47f,.81f)),2e-7f)
    }

    @Test fun analyticAssistKeepsGreysNeutral() {
        for (gray in listOf(0f,.01f,.18f,.9f)) {
            val encoded = VfLogGrade.encodePixelSlog3(floatArrayOf(gray,gray,gray))
            val out = SonyDisplayAssist.apply(encoded)
            assertEquals(out[0],out[1],2e-6f)
            assertEquals(out[1],out[2],2e-6f)
            assertEquals(VfLogGrade.bt709Oetf(gray),out[0],2e-6f)
        }
    }
}

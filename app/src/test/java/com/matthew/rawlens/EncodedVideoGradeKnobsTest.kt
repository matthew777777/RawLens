package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedVideoGradeKnobsTest {
    private val ccm = floatArrayOf(1.6f, -0.4f, -0.2f, -0.3f, 1.5f, -0.2f, 0.05f, -0.5f, 1.45f)

    @Test fun neutralSaturationKeepsCcm() {
        assertArrayEquals(ccm, VfVideoGrade.saturateCcm(ccm, 1f), 0f)
    }

    @Test fun zeroSaturationGivesGrayRows() {
        val m = VfVideoGrade.saturateCcm(ccm, 0f)
        for (c in 0 until 3) {
            assertEquals(m[c], m[3 + c], 1e-6f)
            assertEquals(m[c], m[6 + c], 1e-6f)
        }
    }

    @Test fun saturationPreservesNeutralsAndLuma() {
        val m = VfVideoGrade.saturateCcm(ccm, 1.4f)
        val rgb = floatArrayOf(0.3f, 0.5f, 0.2f)
        fun dev(x: FloatArray) = FloatArray(3) { r -> (0 until 3).sumOf { (x[r * 3 + it] * rgb[it]).toDouble() }.toFloat() }
        val a = dev(ccm); val b = dev(m)
        val la = 0.2126f * a[0] + 0.7152f * a[1] + 0.0722f * a[2]
        val lb = 0.2126f * b[0] + 0.7152f * b[1] + 0.0722f * b[2]
        assertEquals(la, lb, 1e-5f)
        for (i in 0 until 3) assertEquals(la + 1.4f * (a[i] - la), b[i], 1e-5f)
    }

    @Test fun packGradeCarriesVibranceInSlot16() {
        val (_, f) = VfVideoGrade.packGrade(intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f), ccm, 0f, false, vibrance = 0.35f)
        assertEquals(0.35f, f[16], 0f)
    }

    @Test fun orientationDegreesMatchExif() {
        assertEquals(90, CaptureOrientation.degrees(90, 0, false))
        assertEquals(0, CaptureOrientation.degrees(90, 270, false))
        assertEquals(180, CaptureOrientation.degrees(90, 90, false))
    }
}

package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [Decimate].
 *
 * Expectations are hand-computed 2x2 block means of the shared 4x4 fixture
 * (e.g. `(0.125 + 0.25 + 0.625 + 0.75) / 4 = 0.4375`) and agree with
 * `cpu_decimate_to_grey` run under the project venv. Exact: the recorded
 * envelope is bit-exact, so the assertions use zero tolerance.
 */
class DecimateTest {

    @Test
    fun `decimateToGrey matches hand-computed block means exactly`() {
        val out = Decimate.decimateToGrey(GreyTest.INPUT)
        assertArrayEquals(floatArrayOf(0.4375f, 0.6875f), out[0], 0.0f)
        assertArrayEquals(floatArrayOf(0.375f, 0.625f), out[1], 0.0f)
    }

    @Test
    fun `decimateToGrey ignores odd trailing row and column`() {
        val img = Array(5) { y -> FloatArray(5) { x -> (y * 5 + x).toFloat() } }
        val out = Decimate.decimateToGrey(img)
        assertEquals(2, out.size)
        assertEquals(2, out[0].size)
        // Block (0,0): (0 + 1 + 5 + 6) / 4 = 3; block (1,1): (12 + 13 + 17 + 18) / 4 = 15.
        assertEquals(3.0f, out[0][0], 0.0f)
        assertEquals(15.0f, out[1][1], 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decimateToGrey rejects ragged input`() {
        Decimate.decimateToGrey(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f)))
    }
}

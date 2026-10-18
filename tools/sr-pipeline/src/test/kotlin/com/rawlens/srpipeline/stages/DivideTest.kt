package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.core.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows

/**
 * Tests for [Divide] (utils.py `divide` contract).
 *
 * Golden cross-checked with the venv reference python (`-B`, read-only,
 * real `divide`):
 * ```
 * num [[0.5, 1.0, 0.0],[1.5, 0.0, 2.0]] / den [[2.0, 4.0, 0.0],[0.5, 0.0, 0.0]]
 *   -> [[0.25, 0.25, NaN],[3.0, NaN, Inf]]
 * ```
 */
class DivideTest {

    @Test
    fun `array divide matches golden including ieee edges`() {
        val num = Array(1) { Array(2) { FloatArray(3) } }
        num[0][0] = floatArrayOf(0.5f, 1.0f, 0.0f)
        num[0][1] = floatArrayOf(1.5f, 0.0f, 2.0f)
        val den = Array(1) { Array(2) { FloatArray(3) } }
        den[0][0] = floatArrayOf(2.0f, 4.0f, 0.0f)
        den[0][1] = floatArrayOf(0.5f, 0.0f, 0.0f)
        Divide.divide(num, den)
        assertEquals(0.25f, num[0][0][0], 1e-6f)
        assertEquals(0.25f, num[0][0][1], 1e-6f)
        assertTrue(num[0][0][2].isNaN())
        assertEquals(3.0f, num[0][1][0], 1e-6f)
        assertTrue(num[0][1][1].isNaN())
        assertTrue(num[0][1][2].isInfinite())
    }

    @Test
    fun `image divide matches golden`() {
        val num = Image(1, 2, 3, floatArrayOf(0.5f, 1.0f, 0.0f, 1.5f, 0.0f, 2.0f))
        val den = Image(1, 2, 3, floatArrayOf(2.0f, 4.0f, 0.0f, 0.5f, 0.0f, 0.0f))
        Divide.divide(num, den)
        assertEquals(0.25f, num[0, 0, 0], 1e-6f)
        assertEquals(0.25f, num[0, 0, 1], 1e-6f)
        assertTrue(num[0, 0, 2].isNaN())
        assertEquals(3.0f, num[0, 1, 0], 1e-6f)
        assertTrue(num[0, 1, 1].isNaN())
        assertTrue(num[0, 1, 2].isInfinite())
    }

    @Test
    fun `image divide rejects mismatched shapes`() {
        val num = Image.zeros(2, 2, 3)
        val den = Image.zeros(2, 2, 1)
        assertThrows(
            IllegalArgumentException::class.java,
            ThrowingRunnable { Divide.divide(num, den) },
        )
    }
}

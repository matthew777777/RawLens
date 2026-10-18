package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Unit tests for [Gat].
 *
 * Expectations are numpy cross-checks from `cpu_GAT` run under the project
 * venv on the shared 4x4 fixture with `alpha = (1e-2, 2e-2, 3e-2, 4e-2)`
 * and `beta = (1e-6, 2e-6, 3e-6, 4e-6)`; spot values also match hand
 * evaluation, e.g. pixel (0, 0) is an R site so `2 / 1e-2 * sqrt(1e-2 *
 * 0.125 + 3/8 * 1e-4 + 1e-6)` ≈ 7.179. The per-pixel values pin the RGGB
 * -> EXIF-plane mapping (`i = px + py * (3 - 2 * px)`): (0, 1) uses G1
 * (`alpha[1]`) while (1, 0) uses G2 (`alpha[3]`), hence their different
 * outputs.
 */
class GatTest {

    companion object {
        val ALPHA = floatArrayOf(1e-2f, 2e-2f, 3e-2f, 4e-2f)
        val BETA = floatArrayOf(1e-6f, 2e-6f, 3e-6f, 4e-6f)
        val EXPECTED = arrayOf(
            floatArrayOf(7.1791363f, 7.1777434f, 12.310159f, 10.075713f),
            floatArrayOf(8.000625f, 10.075382f, 9.434511f, 11.6123495f),
            floatArrayOf(5.1516986f, 6.246599f, 11.249f, 9.435041f),
            floatArrayOf(7.6f, 9.652979f, 9.097252f, 11.247814f),
        )
    }

    @Test
    fun `gat matches numpy cross-checked values`() {
        val out = Gat.gat(GreyTest.INPUT, ALPHA, BETA)
        for (y in EXPECTED.indices) {
            assertArrayEquals(EXPECTED[y], out[y], 1e-4f)
        }
    }

    @Test
    fun `gat clamps negative variance argument to zero`() {
        // a * I + 3/8 a^2 + b < 0 with I = -100: max(0, .) = 0 -> out = 0.
        val out = Gat.gat(arrayOf(floatArrayOf(-100f)), ALPHA, BETA)
        assertArrayEquals(floatArrayOf(0.0f), out[0], 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gat rejects alpha of wrong length`() {
        Gat.gat(GreyTest.INPUT, floatArrayOf(1e-2f, 2e-2f), BETA)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gat rejects non-positive alpha`() {
        Gat.gat(GreyTest.INPUT, floatArrayOf(1e-2f, 0.0f, 3e-2f, 4e-2f), BETA)
    }
}

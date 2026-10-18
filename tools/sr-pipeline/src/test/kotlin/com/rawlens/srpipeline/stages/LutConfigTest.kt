package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests for [NoiseLut] and [BurstConfig].
 *
 * The LUT legs need `-Dsrpipeline.lut=<noise .npz>` (default: the known
 * burst LUT) and pin its curves against the committed
 * `lut_sigma_sq.npy` / `lut_d_sq.npy` fixtures plus the tag-derived
 * profile from `dngread_noise.npy`. They skip when the LUT is absent.
 * The SNR/config legs are fixture-free.
 */
class LutConfigTest {

    private val lutPath: String
        get() = System.getProperty(
            "srpipeline.lut",
            "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution/data/IMG_20260927_134544_522_noise.npz")

    private fun fixDir(): File = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))

    private fun f64(raw: NpyReader.RawNpy): DoubleArray {
        val out = DoubleArray(raw.payload.size / 8)
        ByteBuffer.wrap(raw.payload).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(out)
        return out
    }

    @Test
    fun `lut loads and matches pinned curves`() {
        assumeTrue("LUT missing: $lutPath", File(lutPath).isFile)
        val noise = f64(NpyReader.loadRaw(File(fixDir(), "dngread_noise.npy")))
        val (alpha, beta) = DngRead.noiseAlphaBeta(noise, 6)
        val curves = NoiseLut.load(lutPath, alpha, beta)
        assertEquals(1001, curves.sigmaSq.size)
        assertEquals(1001, curves.dSq.size)
        val wantSigma = NpyReader.load(File(fixDir(), "lut_sigma_sq.npy")).data
        val wantD = NpyReader.load(File(fixDir(), "lut_d_sq.npy")).data
        var maxS = 0f
        var maxD = 0f
        for (i in wantSigma.indices) {
            maxS = maxOf(maxS, kotlin.math.abs(curves.sigmaSq[i] - wantSigma[i]))
            maxD = maxOf(maxD, kotlin.math.abs(curves.dSq[i] - wantD[i]))
        }
        println("lut curves maxabs sigma=$maxS d=$maxD")
        assertEquals(0f, maxS, 0f)
        assertEquals(0f, maxD, 0f)
    }

    @Test
    fun `lut rejects mismatched profile`() {
        assumeTrue("LUT missing: $lutPath", File(lutPath).isFile)
        try {
            NoiseLut.load(lutPath, doubleArrayOf(1.0, 1.0, 1.0, 1.0), doubleArrayOf(1e-9, 1e-9, 1e-9, 1e-9))
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("alpha"))
        }
    }

    @Test
    fun `lerp and tile decisions match`() {
        assertEquals(0.33, BurstConfig.lerp(6.0, 0.33, 0.25), 0.0)
        assertEquals(0.25, BurstConfig.lerp(30.0, 0.33, 0.25), 0.0)
        assertEquals(0.29, BurstConfig.lerp(18.0, 0.33, 0.25), 1e-12)
        assertEquals(64, BurstConfig.tileSizeForSnr(10.0))
        assertEquals(32, BurstConfig.tileSizeForSnr(18.0))
        assertEquals(16, BurstConfig.tileSizeForSnr(25.0))
        val fin = BurstConfig.finalize(18.0)
        assertEquals(listOf(32, 32, 32, 16), fin.tileSizes)
        assertEquals(4.0, fin.kDenoise, 1e-12)
    }

    @Test
    fun `snr estimator matches hand computation`() {
        // 4x4 constant 0.5 RGGB: top = 16*0.25, bot = 16*(a*0.5+b).
        val img = Array(4) { FloatArray(4) { 0.5f } }
        val got = BurstConfig.estimateSnr(img, doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4),
            doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6))
        val want = 20.0 * kotlin.math.log10(kotlin.math.sqrt(16 * 0.25 / (16 * (1e-4 * 0.5 + 1e-6))))
        assertEquals(want, got, 1e-9)
    }

    @Test
    fun `sanitize accepts feasible and rejects infeasible`() {
        BurstConfig.sanitize(1f, 16, listOf(1, 2, 4, 4), listOf(16, 16, 16, 8), 512, 512)
        try {
            BurstConfig.sanitize(1f, 16, listOf(1, 2, 4, 4), listOf(16, 16, 16, 8), 8, 8)
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("incompatible"))
        }
    }
}

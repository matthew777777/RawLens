// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import com.matthew.burstrecon.DngAdmission
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DNG import fidelity: codes, phase, calibration, and noise model survive. */
class DngFrameLoaderTest {
    private fun dngDir(): File {
        // Module dir at test runtime: tools/sr-vulkan.
        val here = File(System.getProperty("user.dir"))
        val dir = File(here, "../../captures/dcg/vulkan")
        assertTrue("capture DNGs missing: ${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    @Test
    fun noiseProfileSurvivesImport() {
        // The source-* pair carries NoiseProfile (the adjacent pair omits it).
        val loaded = DngFrameLoader.loadFiles(
            listOf(File(dngDir(), "source-low.dng"), File(dngDir(), "source-high.dng")),
            intArrayOf(0, 0, 512, 512)
        )
        assertEquals(2, loaded.size)
        for ((index, frame) in loaded.withIndex()) {
            val admitted = frame.admitted
            assertNotNull("frame $index admitted noiseProfile", admitted.noiseProfile)
            assertEquals(6, admitted.noiseProfile!!.size)
            assertTrue(
                "frame $index noise finite/positive",
                admitted.noiseProfile!!.all { it.isFinite() && it >= 0.0 }
            )
            val meta = frame.metadata.noiseProfile?.toDoubleArray()
            assertNotNull("frame $index metadata noiseProfile", meta)
            val rgb = DngNoiseProfile.toRgb(meta, frame.metadata.cfaPattern!!)
            assertNotNull("frame $index toRgb", rgb)
            val scaled = RawSrMergedNoise.scaleProfile(meta, frame.metadata.cfaPattern!!, 2.0)
            assertNotNull("frame $index scaleProfile", scaled)
        }
    }

    @Test
    fun cfaPhaseRoundTrips() {
        val loaded = DngFrameLoader.load(dngDir(), limit = 2, crop = intArrayOf(0, 0, 512, 512))
        for ((index, frame) in loaded.withIndex()) {
            val admitted = frame.admitted
            val sensor = frame.metadata.cfaPattern!!
            // Re-derive the crop phase exactly like RawSrPackedFrame does:
            // sensor pattern shifted by (origin + crop).
            val cropPhase = sensor.shifted(admitted.sensorLeft, admitted.sensorTop)
            assertEquals(
                "frame $index crop phase",
                admitted.pattern.name, cropPhase.name
            )
            // And back: shifting the crop phase by the origin restores sensor.
            val back = BayerPattern.valueOf(admitted.pattern.shifted(
                admitted.sensorLeft, admitted.sensorTop).name)
            assertEquals("frame $index sensor pattern", sensor, back)
        }
    }

    @Test
    fun codesMatchSamples() {
        val loaded = DngFrameLoader.load(dngDir(), limit = 2, crop = intArrayOf(0, 0, 64, 64))
        val admitted = loaded.first().admitted
        val codes = admitted.codes!!
        assertEquals(admitted.width * admitted.height, codes.size)
        // Independent renormalization must reproduce the admitted samples.
        val black = admitted.blackLevels
        val white = admitted.whiteLevel
        for (y in 0 until admitted.height) for (x in 0 until admitted.width) {
            val i = y * admitted.width + x
            val code = codes[i].toInt() and 0xFFFF
            val sy = admitted.sensorTop + y
            val sx = admitted.sensorLeft + x
            val b = black[((sy and 1) shl 1) or (sx and 1)]
            val expect = (code - b) / (white - b)
            assertEquals("sample $x,$y", expect, admitted.samples[i], 1e-6f)
        }
    }

    @Test
    fun calibrationRoundTrips() {
        val loaded = DngFrameLoader.load(dngDir(), limit = 2)
        val admitted = loaded.first().admitted
        val meta = loaded.first().metadata
        // Writer transposes column-major metadata back to row-major DNG:
        // transposing the stored value must reproduce the source tag.
        fun untranspose(columnMajor: DoubleArray): DoubleArray =
            DoubleArray(9) { columnMajor[(it % 3) * 3 + it / 3] }
        val back1 = untranspose(meta.colorMatrix1!!.toDoubleArray())
        assertTrue(
            "ColorMatrix1 round-trip",
            back1.indices.all {
                kotlin.math.abs(back1[it] - admitted.colorMatrix1!![it]) < 1e-12
            }
        )
    }
}

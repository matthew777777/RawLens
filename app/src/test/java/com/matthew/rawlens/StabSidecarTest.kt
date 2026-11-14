// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabSidecarTest {
    private fun header() = StabSidecar.Header(
        fps = 30, encodeW = 3840, encodeH = 2160, timeOriginNs = 1_000_000_000L,
        sensorOrientationDeg = 90, frontFacing = false,
        focalMm = 6.0f, sensorWMm = 8.0f, sensorHMm = 6.0f,
        sensorW = 8000, sensorH = 6000,
        cropLeft = 0, cropTop = 750, cropW = 8000, cropH = 4500,
        mapperVersion = GyroCameraFrameMapper.MAPPER_VERSION
    )

    private fun take() = StabSidecar.Take(
        header = header(),
        frameTimestampsNs = longArrayOf(1_000_000_000L, 1_033_333_333L, 1_066_666_666L),
        gyro = listOf(
            GyroSample(999_000_000L, 0.01f, -0.02f, 0.003f),
            GyroSample(1_005_000_000L, 0.011f, -0.021f, 0.004f),
            GyroSample(1_040_000_000L, 0.012f, -0.022f, 0.005f)
        )
    )

    @Test fun `render then parse round-trips the take`() {
        val parsed = StabSidecar.parse(StabSidecar.render(take()))
        assertNotNull(parsed)
        assertEquals(header(), parsed!!.header)
        assertTrue(take().frameTimestampsNs.contentEquals(parsed.frameTimestampsNs))
        assertEquals(take().gyro.size, parsed.gyro.size)
        take().gyro.forEachIndexed { i, s ->
            assertEquals(s.timestampNanos, parsed.gyro[i].timestampNanos)
            assertEquals(s.xRadiansPerSecond, parsed.gyro[i].xRadiansPerSecond, 1e-6f)
            assertEquals(s.yRadiansPerSecond, parsed.gyro[i].yRadiansPerSecond, 1e-6f)
            assertEquals(s.zRadiansPerSecond, parsed.gyro[i].zRadiansPerSecond, 1e-6f)
        }
    }

    @Test fun `render is canonical with the format header first`() {
        val json = StabSidecar.render(take())
        assertTrue(json.startsWith("{\"format\":\"rawlens-stab-sidecar\",\"formatVersion\":1,"))
    }

    @Test fun `parse rejects wrong format and version`() {
        val json = StabSidecar.render(take())
        assertNull(StabSidecar.parse(json.replace("rawlens-stab-sidecar", "other")))
        assertNull(StabSidecar.parse(json.replace("\"formatVersion\":1", "\"formatVersion\":3")))
    }

    @Test fun `parse rejects malformed and truncated input without throwing`() {
        assertNull(StabSidecar.parse(""))
        assertNull(StabSidecar.parse("{"))
        assertNull(StabSidecar.parse("not json"))
        assertNull(StabSidecar.parse("[]"))
        val json = StabSidecar.render(take())
        assertNull(StabSidecar.parse(json.substring(0, json.length / 2)))
        assertNull(StabSidecar.parse(json + "trailing"))
    }

    @Test fun `parse rejects empty frame table and empty gyro`() {
        val noFrames = take().copy(frameTimestampsNs = longArrayOf())
        assertNull(StabSidecar.parse(StabSidecar.render(noFrames)))
        val noGyro = take().copy(gyro = emptyList())
        assertNull(StabSidecar.parse(StabSidecar.render(noGyro)))
    }

    @Test fun `parse rejects unsorted timestamps`() {
        val bad = take().copy(frameTimestampsNs = longArrayOf(2L, 1L))
        assertNull(StabSidecar.parse(StabSidecar.render(bad)))
        val badGyro = take().copy(
            gyro = listOf(
                GyroSample(2L, 0f, 0f, 0f),
                GyroSample(1L, 0f, 0f, 0f)
            )
        )
        assertNull(StabSidecar.parse(StabSidecar.render(badGyro)))
    }

    @Test fun `parse rejects implausible geometry`() {
        val badFocal = take().copy(header = header().copy(focalMm = 0f))
        assertNull(StabSidecar.parse(StabSidecar.render(badFocal)))
        val badEncode = take().copy(header = header().copy(encodeW = 0))
        assertNull(StabSidecar.parse(StabSidecar.render(badEncode)))
        val badCrop = take().copy(header = header().copy(cropH = -1))
        assertNull(StabSidecar.parse(StabSidecar.render(badCrop)))
    }

    @Test fun `output mode heals unknown names to OG only`() {
        assertEquals(StabOutputMode.OG_ONLY, StabOutputMode.fromName(null))
        assertEquals(StabOutputMode.OG_ONLY, StabOutputMode.fromName(""))
        assertEquals(StabOutputMode.OG_ONLY, StabOutputMode.fromName("BOGUS"))
        assertEquals(StabOutputMode.BOTH, StabOutputMode.fromName("BOTH"))
        assertEquals(StabOutputMode.BOTH, StabOutputMode.STABILIZED_ONLY.next())
        assertEquals(StabOutputMode.OG_ONLY, StabOutputMode.BOTH.next())
    }

    private fun extras() = StabSidecar.Extras(
        lensIntrinsics = floatArrayOf(6000f, 6001f, 4000f, 3000f, 0f),
        lensDistortion = floatArrayOf(0.01f, -0.002f, 0f, 0.0001f, -0.0002f),
        preCorrLeft = 8, preCorrTop = 8, preCorrW = 8000, preCorrH = 6000,
        activeLeft = 16, activeTop = 16, activeW = 7984, activeH = 5984,
        exposureNs = longArrayOf(8_000_000L, 8_000_000L, -1L),
        rollingShutterSkewNs = longArrayOf(20_000_000L, 20_000_000L, 20_000_000L),
        frameIntrinsics = FloatArray(15) { if (it == 7) Float.NaN else it.toFloat() },
        ois = listOf(StabSidecar.OisSample(999_500_000L, 1.5f, -0.25f)),
        accel = listOf(GyroSample(1_000_000_000L, 0.1f, 9.8f, 0.2f)),
    )

    @Test fun `v2 extras round-trip`() {
        val json = StabSidecar.render(take().copy(extras = extras()))
        assertTrue(json.contains("\"formatVersion\":2"))
        val e = StabSidecar.parse(json)!!.extras!!
        val x = extras()
        assertTrue(x.lensIntrinsics!!.contentEquals(e.lensIntrinsics!!))
        assertTrue(x.lensDistortion!!.contentEquals(e.lensDistortion!!))
        assertEquals(8, e.preCorrLeft); assertEquals(6000, e.preCorrH)
        assertEquals(16, e.activeTop); assertEquals(7984, e.activeW)
        assertTrue(x.exposureNs.contentEquals(e.exposureNs))
        assertTrue(x.rollingShutterSkewNs.contentEquals(e.rollingShutterSkewNs))
        assertEquals(15, e.frameIntrinsics.size)
        assertTrue(e.frameIntrinsics[7].isNaN())
        assertEquals(3f, e.frameIntrinsics[3], 1e-6f)
        assertEquals(x.ois, e.ois)
        assertEquals(1, e.accel.size)
        assertEquals(9.8f, e.accel[0].yRadiansPerSecond, 1e-6f)
    }

    @Test fun `v1 sidecars still parse without extras`() {
        val json = StabSidecar.render(take())
        assertTrue(json.contains("\"formatVersion\":1"))
        assertNull(StabSidecar.parse(json)!!.extras)
    }

    @Test fun `v2 rejects per-frame arrays of the wrong length`() {
        val bad = extras().let {
            StabSidecar.Extras(lensIntrinsics = it.lensIntrinsics, exposureNs = longArrayOf(1L, 2L))
        }
        assertNull(StabSidecar.parse(StabSidecar.render(take().copy(extras = bad))))
    }
}

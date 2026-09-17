// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BurstSidecarTest {
    @Test fun `subfolder accepts the IMG stem and rejects traversal`() {
        assertEquals("IMG_123_F00", BurstSidecar.requireSubfolder("IMG_123_F00"))
        for (bad in listOf("../x", "a/b", "a b", "", "IMG_1.jpg")) {
            try {
                BurstSidecar.requireSubfolder(bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test fun `gyro file name mirrors the DNG stem under gyro dir`() {
        assertEquals(
            "gyro/IMG_123_F00.csv",
            BurstSidecar.gyroFileName("IMG_123_F00.dng")
        )
    }

    @Test fun `csv display name strips the gyro dir and rejects the rest`() {
        assertEquals("IMG_1_F00.csv", BurstSidecar.csvDisplayName("gyro/IMG_1_F00.csv"))
        for (bad in listOf("IMG_1_F00.csv", "gyro/a/b.csv", "gyro/", "other/IMG_1_F00.csv")) {
            try {
                BurstSidecar.csvDisplayName(bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test fun `gyro file name round-trips through display name`() {
        val dng = "IMG_20260910_133342_527_F00.dng"
        assertEquals(
            "IMG_20260910_133342_527_F00.csv",
            BurstSidecar.csvDisplayName(BurstSidecar.gyroFileName(dng))
        )
    }

    @Test fun `intrinsics happy path and missing calibration`() {
        val k = BurstSidecar.intrinsicsOrNull(
            focalMm = 4.3f, sensorWidthMm = 5.76f, sensorHeightMm = 4.29f,
            storedWidthPx = 4000, storedHeightPx = 3000, exifOrientation = 1
        )!!
        assertEquals(4.3 * 4000 / 5.76, k.fxPixels, 1e-3)
        assertEquals(4.3 * 3000 / 4.29, k.fyPixels, 1e-3)
        assertEquals(2000.0, k.cxPixels, 0.0)
        assertEquals(1500.0, k.cyPixels, 0.0)
        assertNull(
            BurstSidecar.intrinsicsOrNull(
                null, 5.76f, 4.29f, 4000, 3000, 1
            )
        )
    }

    @Test fun `intrinsics swaps sensor dims on EXIF transpose`() {
        val plain = BurstSidecar.intrinsicsOrNull(
            4.3f, 5.76f, 4.29f, 4000, 3000, 1
        )!!
        val transposed = BurstSidecar.intrinsicsOrNull(
            4.3f, 5.76f, 4.29f, 4000, 3000, 6
        )!!
        assertEquals(plain.fyPixels * 4000.0 / 3000.0, transposed.fxPixels, 1e-3)
        assertEquals(plain.fxPixels * 3000.0 / 4000.0, transposed.fyPixels, 1e-3)
    }

    @Test fun `meta json is deterministic and marks missing gyro null`() {
        val frames = listOf(
            BurstSidecar.FrameMeta(
                "IMG_1_F00.dng", 100L, 10_000_000L, 0L, 100, "gyro/IMG_1_F00.csv"
            ),
            BurstSidecar.FrameMeta(
                "IMG_1_F01.dng", 200L, 10_000_000L, 0L, 100, null
            )
        )
        val a = BurstSidecar.buildMetaJson("IMG_1", 90, false, 0, 1, null, frames)
        val b = BurstSidecar.buildMetaJson("IMG_1", 90, false, 0, 1, null, frames)
        assertEquals(a, b)
        assertEquals(
            "{\"format\":\"rawlens-burst-meta\"," +
                "\"formatVersion\":1," +
                "\"burstName\":\"IMG_1\"," +
                "\"mapperVersion\":1," +
                "\"sensorOrientationDeg\":90," +
                "\"frontFacing\":false," +
                "\"deviceOrientationDeg\":0," +
                "\"exifOrientation\":1," +
                "\"intrinsics\":null," +
                "\"frames\":[" +
                "{\"file\":\"IMG_1_F00.dng\",\"timestampNanos\":100," +
                "\"exposureNanos\":10000000,\"skewNanos\":0,\"iso\":100," +
                "\"gyro\":\"gyro/IMG_1_F00.csv\"}," +
                "{\"file\":\"IMG_1_F01.dng\",\"timestampNanos\":200," +
                "\"exposureNanos\":10000000,\"skewNanos\":0,\"iso\":100," +
                "\"gyro\":null}]}",
            a
        )
    }
}

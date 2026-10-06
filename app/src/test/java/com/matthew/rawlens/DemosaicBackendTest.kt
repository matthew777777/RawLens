// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM gates for the demosaic-backend switches (Video tab MHC/RCD,
 * JPEG tab AMaZE/RCD): preference round-trips/defaults plus the
 * Bayer-pattern -> RCD quad-offset map the JPEG bridge packs.
 */
class DemosaicBackendTest {
    @Test fun jpegDemosaicDefaultsToAmaze() {
        assertEquals(JpegDemosaic.AMAZE, JpegDemosaic.fromPreference(null))
        assertEquals(JpegDemosaic.AMAZE, JpegDemosaic.fromPreference("BOGUS"))
        assertEquals(JpegDemosaic.AMAZE, JpegOutputSettings().demosaic)
    }

    @Test fun jpegDemosaicRoundTrips() {
        assertEquals(JpegDemosaic.RCD, JpegDemosaic.fromPreference("RCD"))
        assertEquals(JpegDemosaic.AMAZE, JpegDemosaic.fromPreference("AMAZE"))
        assertEquals(JpegDemosaic.RCD, JpegDemosaic.AMAZE.next())
        assertEquals(JpegDemosaic.AMAZE, JpegDemosaic.RCD.next())
        assertEquals(JpegDemosaic.RCD, JpegOutputSettings().copy(demosaic = JpegDemosaic.RCD).demosaic)
    }

    @Test fun videoDemosaicDefaultsToMhc() {
        assertEquals(VideoDemosaic.MHC, VideoDemosaic.fromPreference(null))
        assertEquals(VideoDemosaic.MHC, VideoDemosaic.fromPreference("BOGUS"))
    }

    @Test fun videoDemosaicRoundTrips() {
        assertEquals(VideoDemosaic.RCD, VideoDemosaic.fromPreference("RCD"))
        assertEquals(VideoDemosaic.MHC, VideoDemosaic.fromPreference("MHC"))
        assertEquals(VideoDemosaic.RCD, VideoDemosaic.MHC.next())
        assertEquals(VideoDemosaic.MHC, VideoDemosaic.RCD.next())
    }

    @Test fun rcdChannelsMatchPatterns() {
        // Exact Gr/Gb-ordered maps (regression-pinned)…
        assertTrue(VfRcd.channelsFromPattern(BayerPattern.RGGB).contentEquals(intArrayOf(0, 1, 2, 3)))
        assertTrue(VfRcd.channelsFromPattern(BayerPattern.BGGR).contentEquals(intArrayOf(3, 2, 1, 0)))
        assertTrue(VfRcd.channelsFromPattern(BayerPattern.GRBG).contentEquals(intArrayOf(1, 0, 3, 2)))
        assertTrue(VfRcd.channelsFromPattern(BayerPattern.GBRG).contentEquals(intArrayOf(2, 3, 0, 1)))
        // …and the routing contract itself: every quad resolves to the
        // pattern's true color through the channels[] convention.
        for (pattern in BayerPattern.entries) {
            val channels = VfRcd.channelsFromPattern(pattern)
            for (q in 0..3) {
                val routed = when {
                    channels[0] == q -> 0
                    channels[1] == q || channels[2] == q -> 1
                    channels[3] == q -> 2
                    else -> -1
                }
                val truth = pattern.colorOrdinalAt(q and 1, q shr 1)
                assertEquals("$pattern quad $q routes to $routed, want $truth", truth, routed)
            }
        }
    }
}

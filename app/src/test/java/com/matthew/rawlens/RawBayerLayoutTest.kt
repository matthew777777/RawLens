// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawBayerLayoutTest {
    @Test fun `binned output never requires remosaic`() {
        // vivo X200U regression: honestly-reported binned output (rawBinning=true, static
        // 2x2 group) is regular Bayer the HAL already remosaiced, not grouped mosaic.
        assertFalse(RawBayerLayout.requiresRemosaic(true, true, null, 2, 2))
        assertFalse(
            RawBayerLayout.requiresRemosaic(true, true, RawBayerLayout.PIXEL_MODE_DEFAULT, 2, 2)
        )
        // Contradictory keys fail safe to Bayer: binned evidence vetoes.
        assertFalse(
            RawBayerLayout.requiresRemosaic(
                true, true, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION, 2, 2
            )
        )
        // Default pixel mode is the binned stream mode, regardless of the binning flag.
        assertFalse(
            RawBayerLayout.requiresRemosaic(null, true, RawBayerLayout.PIXEL_MODE_DEFAULT, 2, 2)
        )
        assertFalse(
            RawBayerLayout.requiresRemosaic(false, true, RawBayerLayout.PIXEL_MODE_DEFAULT, 2, 2)
        )
    }

    @Test fun `grouped mosaic needs positive unbinned evidence`() {
        // Missing per-capture keys fail safe to the regular Bayer path.
        assertFalse(RawBayerLayout.requiresRemosaic(null, true, null, 2, 2))
    }

    @Test fun `unbinned full-res UHR output requires remosaic`() {
        assertTrue(
            RawBayerLayout.requiresRemosaic(
                false, true, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION, 2, 2
            )
        )
        assertTrue(RawBayerLayout.requiresRemosaic(false, true, null, 2, 2))
        assertTrue(
            RawBayerLayout.requiresRemosaic(
                null, true, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION, 2, 2
            )
        )
    }

    @Test fun `remosaic needs UHR sensor and grouped CFA`() {
        assertFalse(
            RawBayerLayout.requiresRemosaic(
                false, false, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION, 2, 2
            )
        )
        assertFalse(
            RawBayerLayout.requiresRemosaic(
                false, true, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION, 1, 1
            )
        )
        assertFalse(
            RawBayerLayout.requiresRemosaic(
                false, true, RawBayerLayout.PIXEL_MODE_MAXIMUM_RESOLUTION
            )
        )
    }
}

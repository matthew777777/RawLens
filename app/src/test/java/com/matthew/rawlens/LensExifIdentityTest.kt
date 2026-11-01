package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LensExifIdentityTest {
    @Test fun opticalIdentityUsesEquivalentAndStableDecimalFormatting() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for ((mm, type) in listOf(14 to "ultra wide", 18 to "ultra wide", 23 to "wide", 40 to "telephoto", 150 to "ultra telephoto")) {
                assertEquals("Phone $type camera ${mm}mm f/1.8",
                    LensExifIdentity.model("Phone", mm.toFloat(), 36f, 24f, 1.8f))
            }
            assertEquals("Phone wide camera 24mm f/1.8",
                LensExifIdentity.model("Phone", 6f, 9f, 6f, 1.8f))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun missingOrInvalidOpticsOmitModel() {
        for (invalid in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(LensExifIdentity.model("Phone", invalid, 9f, 6f, 2f))
            assertNull(LensExifIdentity.model("Phone", 6f, invalid, 6f, 2f))
            assertNull(LensExifIdentity.model("Phone", 6f, 9f, 6f, invalid))
        }
        assertNull(LensExifIdentity.make("  "))
        assertNull(LensExifIdentity.make("Brand\u0000"))
        assertEquals("OPPO", LensExifIdentity.make(" OPPO "))
    }
}

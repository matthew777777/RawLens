// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class JpegImageDescriptionTest {
    @Test fun basicReportsItsOwnCurveAndActiveControlsWithoutAgxClaims() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val settings = JpegOutputSettings(toneMapper = PhotoToneMapper.BASIC,
                agxLook = AgxLook.PUNCHY, agxContrast = 1.4f,
                highlightShoulder = .75f, displayP3 = true, jpegQuality = 92)
            val text = jpegImageDescription(settings, null, null, outputWidth = 4080, outputHeight = 3060)
            assertTrue(text.contains("RAW_SENSOR -> ACEScg -> Basic -> Display P3"))
            assertFalse(text.contains("AgX"))
            for (field in listOf("PARAMETERS", "DEVELOPMENT SETTINGS", "brightness gamma 1.100",
                "cubic contrast blend 0.750", "strength 0.750", "Dimensions: 4080x3060",
                "JPEG quality: 92", "Demosaic preference", "False-color suppression", "Purple defringe")) {
                assertTrue(field, text.contains(field))
            }
        } finally { Locale.setDefault(old) }
    }
    @Test fun agxRecordsAllStoredAndEffectiveControls() {
        val s = JpegOutputSettings(toneMapper = PhotoToneMapper.AGX, agxLook = AgxLook.PUNCHY, agxContrast = 1.2f,
            agxSaturation = .8f, agxPurityBoost = 1.1f, agxHuePreservation = .3f,
            agxShadowEv = 9f, agxHighlightEv = 6f, agxGamutCompression = .4f,
            activeDLighting = ActiveDLighting.NORMAL)
        val text = jpegImageDescription(s, null, null)
        for (field in listOf("AgX Punchy", "contrast: 1.200", "saturation: 0.800", "purity boost: 1.100",
            "hue preservation: 0.300", "shadow range: 9.000 EV; effective", "highlight range: 6.000 EV",
            "gamut compression: 0.400", "exponential shoulder")) assertTrue(field, text.contains(field))
        assertFalse(text.contains("Basic response"))
    }
    @Test fun mergeProvenanceIsPreservedAndActualHdrFallbackIsTruthful() {
        val provenance = "Captured with RawLens\nLK-R merge\nPARAMETERS\n- Frames: 8\nTIMINGS\n- Merge: 123 ms"
        val s = JpegOutputSettings(toneMapper = PhotoToneMapper.BASIC, ultraHdr = true)
        val fallback = jpegImageDescription(s, CaptureFileNames.TYPE_LKR, provenance,
            ultraHdrEncoded = false, encodeMs = 42)
        assertTrue(fallback.startsWith(provenance))
        assertTrue(fallback.contains("Merged linear camera RGB -> ACEScg -> Basic"))
        assertFalse(fallback.contains("AMaZE"))
        assertTrue(fallback.contains("no additional demosaic"))
        assertTrue(fallback.contains("SDR fallback written"))
        assertFalse(fallback.contains("-> Ultra HDR gainmap"))
        assertTrue(fallback.contains("JPEG encode: 42 ms"))
        val hdr = jpegImageDescription(s, null, null, ultraHdrEncoded = true)
        assertTrue(hdr.contains("-> Ultra HDR gainmap (JPEG_R)"))
        assertTrue(hdr.contains("Gainmap quality"))
        assertFalse(hdr.contains("SDR fallback"))
    }
}
